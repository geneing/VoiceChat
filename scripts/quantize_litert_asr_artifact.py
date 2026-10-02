#!/usr/bin/env python3
"""Apply and audit LiteRT ASR FP16 or calibrated INT8 quantization.

Run through scripts/quantize-litert-asr-artifact.sh so the pinned sample and
the isolated WSL conversion environment are used.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
from pathlib import Path
import sys
import tempfile
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
MODELS_ROOT = (ROOT / "models").resolve()
TEST_DATA_ROOT = (ROOT / "test_data").resolve()
LOCAL_TEMP_CALIBRATION_ROOT = (
    Path(tempfile.gettempdir()) / "voicechat-litert-asr-calibration"
).resolve()
SAMPLE_COMMIT_FILE = ROOT / "scripts" / "litert-sample-source.commit"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _inside(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _repo_path(value: str, root: Path, *, must_exist: bool) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = ROOT / path
    path = path.resolve(strict=must_exist)
    if not _inside(path, root):
        raise ValueError(f"Path must resolve inside {root.name}/: {value}")
    if must_exist and not path.is_file():
        raise ValueError(f"Expected a file: {path}")
    if path.suffix.lower() != ".tflite" and root == MODELS_ROOT:
        raise ValueError(f"LiteRT artifact path must end in .tflite: {value}")
    return path


def _calibration_audio_path(value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = ROOT / path
    path = path.resolve(strict=True)
    if not (_inside(path, TEST_DATA_ROOT) or _inside(path, LOCAL_TEMP_CALIBRATION_ROOT)):
        raise ValueError(
            "Calibration WAVs must be in test_data/ or the private "
            "/tmp/voicechat-litert-asr-calibration/ directory"
        )
    if not path.is_file() or path.suffix.lower() != ".wav":
        raise ValueError(f"Calibration input must be a local WAV file: {value}")
    return path


def _signature_info(path: Path) -> dict[str, dict[str, Any]]:
    from ai_edge_litert import interpreter

    model = interpreter.Interpreter(model_path=str(path), num_threads=1)
    result: dict[str, dict[str, Any]] = {}
    for signature_key, signature in model.get_signature_list().items():
        runner = model.get_signature_runner(signature_key)
        inputs = runner.get_input_details()
        outputs = runner.get_output_details()
        def metadata(details: dict[str, Any]) -> dict[str, Any]:
            quantization = details.get("quantization_parameters", {})
            return {
                "shape": [int(item) for item in details["shape"]],
                "dtype": str(details["dtype"]),
                "quantization_scales": [
                    float(item) for item in quantization.get("scales", [])
                ],
                "quantization_zero_points": [
                    int(item) for item in quantization.get("zero_points", [])
                ],
            }
        result[signature_key] = {
            "inputs": {
                name: metadata(inputs[name])
                for name in signature["inputs"]
            },
            "outputs": {
                name: metadata(outputs[name])
                for name in signature["outputs"]
            },
        }
    return result


def _signature_shape_contract(path: Path) -> dict[str, Any]:
    """Returns the sample signature input names, shapes, and NumPy dtypes."""
    from ai_edge_litert import interpreter

    model = interpreter.Interpreter(model_path=str(path), num_threads=1)
    result = {}
    for key, signature in model.get_signature_list().items():
        runner = model.get_signature_runner(key)
        detail_by_name = runner.get_input_details()
        ordered_inputs = signature["inputs"]
        result[key] = {
            "input_names": ordered_inputs,
            "input_details": {
                name: detail_by_name[name] for name in ordered_inputs
            },
        }
    return result


def _flatbuffer_audit(path: Path) -> dict[str, Any]:
    """Counts FP16/INT8 tensors and audits internal operator output types."""
    from ai_edge_quantizer.utils import tfl_flatbuffer_utils
    from ai_edge_litert import schema_py_generated as schema

    model = tfl_flatbuffer_utils.read_model(path)
    type_names = {
        value: key
        for key, value in vars(schema.TensorType).items()
        if isinstance(value, int)
    }
    tensor_type_counts: collections.Counter[str] = collections.Counter()
    fp16_constant_count = 0
    int8_constant_count = 0
    for subgraph in model.subgraphs:
        for tensor in subgraph.tensors:
            tensor_type = type_names.get(tensor.type, f"UNKNOWN_{tensor.type}")
            tensor_type_counts[tensor_type] += 1
            buffer = model.buffers[tensor.buffer]
            if buffer.data is not None and len(buffer.data) > 0:
                if tensor.type == schema.TensorType.FLOAT16:
                    fp16_constant_count += 1
                elif tensor.type in (schema.TensorType.INT8, schema.TensorType.UINT8):
                    int8_constant_count += 1

    public_outputs: dict[int, set[int]] = collections.defaultdict(set)
    for signature in model.signatureDefs:
        graph_index = int(signature.subgraphIndex)
        for tensor_map in signature.outputs:
            public_outputs[graph_index].add(int(tensor_map.tensorIndex))

    op_name_by_code = {
        value: key
        for key, value in vars(schema.BuiltinOperator).items()
        if isinstance(value, int)
    }
    output_types: collections.Counter[str] = collections.Counter()
    float_output_ops: collections.Counter[str] = collections.Counter()
    int8_output_ops: collections.Counter[str] = collections.Counter()
    all_internal_output_types: collections.Counter[str] = collections.Counter()
    operators_by_type: collections.Counter[str] = collections.Counter()
    operator_count = 0
    for graph_index, subgraph in enumerate(model.subgraphs):
        for operator in subgraph.operators:
            code = model.operatorCodes[operator.opcodeIndex]
            builtin = code.builtinCode
            op_name = op_name_by_code.get(builtin, f"CUSTOM_{code.customCode}")
            operators_by_type[op_name] += 1
            operator_count += 1
            for tensor_index in operator.outputs:
                if tensor_index < 0 or tensor_index in public_outputs[graph_index]:
                    continue
                tensor = subgraph.tensors[tensor_index]
                buffer_data = model.buffers[tensor.buffer].data
                if buffer_data is not None and len(buffer_data) > 0:
                    continue
                tensor_type = type_names.get(tensor.type, f"UNKNOWN_{tensor.type}")
                all_internal_output_types[tensor_type] += 1
                # Integer and boolean outputs are usually indices, masks, or
                # shape/control values, not quantized activation tensors.
                if tensor.type in (
                    schema.TensorType.INT8,
                    schema.TensorType.UINT8,
                    schema.TensorType.FLOAT16,
                    schema.TensorType.FLOAT32,
                ):
                    output_types[tensor_type] += 1
                    if tensor.type == schema.TensorType.FLOAT32:
                        float_output_ops[op_name] += 1
                    elif tensor.type in (
                        schema.TensorType.INT8,
                        schema.TensorType.UINT8,
                    ):
                        int8_output_ops[op_name] += 1

    int8_outputs = output_types["INT8"] + output_types["UINT8"]
    float_outputs = output_types["FLOAT32"] + output_types["FLOAT16"]
    considered_outputs = int8_outputs + float_outputs
    return {
        "tensor_types": dict(sorted(tensor_type_counts.items())),
        "fp16_constant_tensors": fp16_constant_count,
        "int8_constant_tensors": int8_constant_count,
        "internal_operator_output_types": dict(sorted(output_types.items())),
        "all_internal_operator_output_types": dict(
            sorted(all_internal_output_types.items())
        ),
        "operator_count": operator_count,
        "operators_by_type": dict(sorted(operators_by_type.items())),
        "internal_operator_output_count": considered_outputs,
        "internal_int8_operator_outputs": int8_outputs,
        "internal_float_operator_outputs": float_outputs,
        "internal_int8_operator_output_coverage": (
            int8_outputs / considered_outputs if considered_outputs else None
        ),
        "remaining_float_output_ops": dict(sorted(float_output_ops.items())),
        "int8_output_ops": dict(sorted(int8_output_ops.items())),
        "signature_count": len(model.signatureDefs),
        "signature_keys": sorted(
            signature.signatureKey.decode("utf-8")
            for signature in model.signatureDefs
        ),
        "model_tensor_type_enum": "ai_edge_litert.schema_py_generated.TensorType",
    }


def _signature_contract_equal(before: dict[str, Any], after: dict[str, Any]) -> bool:
    if before.keys() != after.keys():
        return False
    for key in before:
        if before[key].keys() != after[key].keys():
            return False
        for direction in ("inputs", "outputs"):
            if before[key][direction].keys() != after[key][direction].keys():
                return False
            for name in before[key][direction]:
                if before[key][direction][name]["shape"] != after[key][direction][name]["shape"]:
                    return False
    return True


def _quantize_public_input(value: Any, detail: dict[str, Any]):
    """Maps source signature input values into an artifact's exposed dtype."""
    import numpy as np

    value = np.asarray(value)
    target_dtype = np.dtype(detail["dtype"])
    if value.dtype == target_dtype:
        return value
    if np.issubdtype(value.dtype, np.floating) and np.issubdtype(
        target_dtype, np.floating
    ):
        return value.astype(target_dtype)
    if not np.issubdtype(target_dtype, np.integer) or not np.issubdtype(
        value.dtype, np.floating
    ):
        raise ValueError(f"Unsupported input conversion {value.dtype} -> {target_dtype}")
    params = detail.get("quantization_parameters", {})
    scales = np.asarray(params.get("scales", []), dtype=np.float64)
    zero_points = np.asarray(params.get("zero_points", []), dtype=np.int64)
    if scales.size != 1 or zero_points.size != 1 or scales[0] <= 0:
        raise ValueError("Quantized signature input lacks per-tensor affine parameters")
    info = np.iinfo(target_dtype)
    quantized = np.rint(value.astype(np.float64) / scales[0] + zero_points[0])
    return np.clip(quantized, info.min, info.max).astype(target_dtype)


def _dequantize_public_output(value: Any, detail: dict[str, Any]):
    """Maps an artifact output back to float for numeric parity checks."""
    import numpy as np

    value = np.asarray(value)
    if not np.issubdtype(value.dtype, np.integer):
        return value.astype(np.float32)
    params = detail.get("quantization_parameters", {})
    scales = np.asarray(params.get("scales", []), dtype=np.float64)
    zero_points = np.asarray(params.get("zero_points", []), dtype=np.int64)
    if scales.size != 1 or zero_points.size != 1 or scales[0] <= 0:
        raise ValueError("Quantized signature output lacks per-tensor affine parameters")
    return (value.astype(np.float64) - zero_points[0]) * scales[0]


def _validate_named_signatures(
    source_path: Path,
    candidate_path: Path,
    calibration_data: dict[str, list[dict[str, Any]]],
    num_threads: int,
) -> dict[str, Any]:
    """Runs all named signatures on CPU and compares public outputs by MSE."""
    import numpy as np
    from ai_edge_litert import interpreter

    source = interpreter.Interpreter(model_path=str(source_path), num_threads=num_threads)
    candidate = interpreter.Interpreter(
        model_path=str(candidate_path), num_threads=num_threads
    )
    result: dict[str, Any] = {}
    for signature_key, samples in calibration_data.items():
        source_runner = source.get_signature_runner(signature_key)
        candidate_runner = candidate.get_signature_runner(signature_key)
        candidate_inputs = candidate_runner.get_input_details()
        candidate_outputs = candidate_runner.get_output_details()
        per_output: dict[str, dict[str, float]] = {}
        for sample in samples:
            source_values = source_runner(**sample)
            candidate_values = candidate_runner(
                **{
                    name: _quantize_public_input(value, candidate_inputs[name])
                    for name, value in sample.items()
                }
            )
            for output_name, source_value in source_values.items():
                source_array = np.asarray(source_value, dtype=np.float32)
                candidate_array = _dequantize_public_output(
                    candidate_values[output_name], candidate_outputs[output_name]
                )
                if candidate_array.shape != source_array.shape:
                    raise ValueError(
                        f"Output shape mismatch for {signature_key}.{output_name}: "
                        f"{candidate_array.shape} != {source_array.shape}"
                    )
                difference = candidate_array - source_array
                stats = per_output.setdefault(
                    output_name,
                    {
                        "samples": 0,
                        "squared_error_sum": 0.0,
                        "element_count": 0,
                        "max_abs_error": 0.0,
                    },
                )
                stats["samples"] += 1
                stats["squared_error_sum"] += float(
                    np.square(difference, dtype=np.float64).sum()
                )
                stats["element_count"] += int(difference.size)
                stats["max_abs_error"] = max(
                    stats["max_abs_error"], float(np.max(np.abs(difference)))
                )
        for stats in per_output.values():
            stats["mean_squared_error"] = (
                stats.pop("squared_error_sum") / stats["element_count"]
            )
        result[signature_key] = {
            "samples_compared": len(samples),
            "outputs": per_output,
            "candidate_input_dtypes": {
                name: str(detail["dtype"])
                for name, detail in candidate_inputs.items()
            },
            "candidate_output_dtypes": {
                name: str(detail["dtype"])
                for name, detail in candidate_outputs.items()
            },
        }
    return result


def _load_quantizer_api():
    from ai_edge_quantizer import algorithm_manager, quantizer, recipe_manager
    from ai_edge_quantizer import recipe
    from ai_edge_quantizer import qtyping

    return algorithm_manager, quantizer, recipe_manager, recipe, qtyping


def _quantize_fp16(input_path: Path, output_path: Path, overwrite: bool) -> dict[str, Any]:
    algorithm_manager, quantizer, recipe_manager, _, qtyping = _load_quantizer_api()

    source_signatures = _signature_info(input_path)
    if output_path.exists() and not overwrite:
        raise FileExistsError(f"Refusing to overwrite existing output: {output_path}")
    if output_path.exists():
        output_path.unlink()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    manager = recipe_manager.RecipeManager()
    manager.add_weight_only_config(
        regex=".*",
        operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
        num_bits=16,
        algorithm_key=algorithm_manager.AlgorithmName.FLOAT_CASTING,
    )
    qt = quantizer.Quantizer(str(input_path))
    qt.load_quantization_recipe(manager.get_quantization_recipe())
    if qt.need_calibration:
        raise RuntimeError("FP16 FLOAT_CASTING unexpectedly requires calibration")
    qt.quantize(
        serialize_to_path=str(output_path),
        enable_progress_report=True,
    )

    if not output_path.is_file() or output_path.stat().st_size == 0:
        raise RuntimeError("Quantizer did not produce a non-empty FP16 artifact")
    audit = _flatbuffer_audit(output_path)
    if audit["fp16_constant_tensors"] <= 0:
        raise RuntimeError("FP16 audit found no FLOAT16 constant tensors")
    if output_path.stat().st_size >= input_path.stat().st_size:
        raise RuntimeError("FP16 artifact is not smaller than the FP32 input")
    output_signatures = _signature_info(output_path)
    if not _signature_contract_equal(source_signatures, output_signatures):
        raise RuntimeError("FP16 artifact changed signature names or tensor shapes")
    if any(
        details["dtype"] != source_signatures[key][direction][name]["dtype"]
        for key, signature in output_signatures.items()
        for direction in ("inputs", "outputs")
        for name, details in signature[direction].items()
    ):
        raise RuntimeError("FP16 artifact changed public signature tensor dtypes")

    return {
        "quantization": "FLOAT_CASTING weight-only FP16; float compute",
        "source_sha256": _sha256(input_path),
        "source_bytes": input_path.stat().st_size,
        "output_sha256": _sha256(output_path),
        "output_bytes": output_path.stat().st_size,
        "size_ratio": output_path.stat().st_size / input_path.stat().st_size,
        "fp16_constant_tensors": audit["fp16_constant_tensors"],
        "signature_keys": audit["signature_keys"],
        "output": str(output_path.relative_to(ROOT)),
    }


MODEL_SPECS = {
    "moonshine-tiny": ("moonshine", "UsefulSensors/moonshine-tiny"),
    "parakeet-ctc": ("parakeet_ctc", "nvidia/parakeet-ctc-0.6b"),
    "parakeet-tdt": ("parakeet_tdt", "nvidia/parakeet-tdt-0.6b-v3"),
    "whisper-tiny": ("whisper", "openai/whisper-tiny"),
    "whisper-small": ("whisper", "openai/whisper-small"),
}


def _load_sample_model(model_name: str, *, override_transformers: bool):
    module_name, model_id = MODEL_SPECS[model_name]
    if module_name == "moonshine":
        from samples.litert.speech_recognition.convert import moonshine as module
    elif module_name == "parakeet_ctc":
        from samples.litert.speech_recognition.convert import parakeet_ctc as module
    elif module_name == "parakeet_tdt":
        from samples.litert.speech_recognition.convert import parakeet_tdt as module
    else:
        from samples.litert.speech_recognition.convert import whisper as module
    return module.__dict__[{
        "moonshine": "Moonshine",
        "parakeet_ctc": "ParakeetCTC",
        "parakeet_tdt": "ParakeetTDT",
        "whisper": "Whisper",
    }[module_name]](model_id=model_id, override_transformers=override_transformers)


def _as_numpy(value: Any):
    import numpy as np

    if hasattr(value, "detach"):
        value = value.detach().cpu().numpy()
    result = np.asarray(value)
    return np.ascontiguousarray(result)


def _prepare_calibration_data(
    model_name: str,
    model_path: Path,
    audio_paths: list[Path],
    input_sec: float,
    *,
    override_transformers: bool,
) -> tuple[dict[str, list[dict[str, Any]]], dict[str, Any]]:
    import librosa
    import numpy as np
    import torch
    from ai_edge_litert import interpreter

    model = _load_sample_model(
        model_name, override_transformers=override_transformers
    )
    processor = model.get_processor()
    audio_duration = input_sec - 0.01 if model_name.startswith("parakeet-") else input_sec
    lite_model = interpreter.Interpreter(model_path=str(model_path), num_threads=1)
    signatures = lite_model.get_signature_list()
    expected_signatures = {"encode"} if model_name == "parakeet-ctc" else {"encode", "decode"}
    if model_name == "parakeet-tdt" and "decode_1" in signatures:
        expected_signatures.add("decode_1")
    if set(signatures) != expected_signatures:
        raise ValueError(
            f"Artifact signatures {sorted(signatures)} do not match the pinned "
            f"{model_name} sample contract {sorted(expected_signatures)}"
        )

    details_by_signature = {}
    for signature_key, signature in signatures.items():
        runner = lite_model.get_signature_runner(signature_key)
        details = runner.get_input_details()
        details_by_signature[signature_key] = {
            name: details[name] for name in signature["inputs"]
        }

    calibration_data: dict[str, list[dict[str, Any]]] = {
        key: [] for key in signatures
    }
    for audio_path in audio_paths:
        duration = min(audio_duration, librosa.get_duration(path=str(audio_path)))
        if duration < input_sec - 0.02:
            raise ValueError(
                f"Calibration audio is too short for this artifact's "
                f"{input_sec:g}s input window: {audio_path.name} ({duration:.3f}s)"
            )
        samples, sample_rate = librosa.load(
            str(audio_path), sr=processor.get_sampling_rate(), duration=audio_duration
        )
        if sample_rate != processor.get_sampling_rate():
            raise ValueError("Pinned sample audio loader returned an unexpected sample rate")
        processed_audio = processor.process(samples)
        encoder_inputs = tuple(model.get_encoder_sample_input(processed_audio))

        with torch.no_grad():
            encoder_outputs = tuple(model.get_encoder()(*encoder_inputs))
            signature_values: dict[str, tuple[Any, ...]] = {"encode": encoder_inputs}
            for signature_key in signatures:
                if signature_key == "encode":
                    continue
                details = details_by_signature[signature_key]
                token_inputs = [
                    (name, detail)
                    for name, detail in details.items()
                    if np.dtype(detail["dtype"]) == np.dtype(np.int32)
                    and len(detail["shape"]) == 2
                ]
                if len(token_inputs) != 1:
                    raise ValueError(
                        f"Cannot identify exactly one int32 token input in "
                        f"signature {signature_key}: {list(details)}"
                    )
                num_tokens = int(token_inputs[0][1]["shape"][1])
                signature_values[signature_key] = tuple(
                    model.get_decoder_sample_input(encoder_outputs, num_tokens)
                )

        for signature_key, values in signature_values.items():
            ordered_names = signatures[signature_key]["inputs"]
            details = details_by_signature[signature_key]
            if len(values) != len(ordered_names):
                raise ValueError(
                    f"Sample produced {len(values)} inputs for {signature_key}, "
                    f"but the exported signature has {len(ordered_names)}"
                )
            sample_record: dict[str, Any] = {}
            for name, value in zip(ordered_names, values, strict=True):
                array = _as_numpy(value)
                expected = details[name]
                if array.shape != tuple(int(dim) for dim in expected["shape"]):
                    raise ValueError(
                        f"Sample/model input shape mismatch for {signature_key}.{name}: "
                        f"{array.shape} != {tuple(expected['shape'])}"
                    )
                if array.dtype != np.dtype(expected["dtype"]):
                    raise ValueError(
                        f"Sample/model input dtype mismatch for {signature_key}.{name}: "
                        f"{array.dtype} != {np.dtype(expected['dtype'])}"
                    )
                sample_record[name] = array
            calibration_data[signature_key].append(sample_record)

    sample_commit = SAMPLE_COMMIT_FILE.read_text(encoding="utf-8").strip()
    signature_summary = {
        key: {
            "calibration_samples": len(items),
            "input_shapes": {
                name: [int(dim) for dim in details["shape"]]
                for name, details in details_by_signature[key].items()
            },
            "input_dtypes": {
                name: str(details["dtype"])
                for name, details in details_by_signature[key].items()
            },
        }
        for key, items in calibration_data.items()
    }
    summary = {
        "model": model_name,
        "hf_model_id": MODEL_SPECS[model_name][1],
        "sample_source_commit": sample_commit,
        "input_window_seconds": input_sec,
        "calibration_sample_count": len(audio_paths),
        "calibration_audio": [
            _calibration_audio_provenance(path) for path in audio_paths
        ],
        "signatures": signature_summary,
    }
    return calibration_data, summary


def _calibration_audio_provenance(path: Path) -> dict[str, Any]:
    sidecar = path.with_suffix(path.suffix + ".json")
    if sidecar.is_file():
        metadata = json.loads(sidecar.read_text(encoding="utf-8"))
        if metadata.get("derived_sha256") != _sha256(path):
            raise ValueError(f"Temporary calibration metadata does not match audio: {path.name}")
        return {
            "name": path.name,
            "sha256": _sha256(path),
            "source_file": metadata.get("source_file"),
            "source_sha256": metadata.get("source_sha256"),
            "source_provenance": metadata.get("provenance"),
            "conversion": {
                "decoder": metadata.get("decoder"),
                "sample_rate_hz": metadata.get("sample_rate_hz"),
                "channels": metadata.get("channels"),
                "sample_format": metadata.get("sample_format"),
                "duration_seconds": metadata.get("duration_seconds"),
            },
        }
    return {
        "name": path.name,
        "sha256": _sha256(path),
        "source_provenance": (
            "Local test_data WAV; exact recording source/license/consent is "
            "unverified per docs/risks-and-decisions.md R-0037."
        ),
    }


def _quantize_int8(args: argparse.Namespace, input_path: Path, output_path: Path) -> dict[str, Any]:
    _, quantizer, _, recipe, _ = _load_quantizer_api()
    audio_paths = [_calibration_audio_path(value) for value in args.calibration_audio]
    data, calibration_summary = _prepare_calibration_data(
        args.model,
        input_path,
        audio_paths,
        args.input_sec,
        override_transformers=True,
    )
    if args.prepare_only:
        return {
            "status": "CALIBRATION_INPUTS_VALIDATED_ONLY",
            "calibration": calibration_summary,
        }
    if output_path.exists() and not args.overwrite:
        raise FileExistsError(f"Refusing to overwrite existing output: {output_path}")
    if output_path.exists():
        output_path.unlink()
    output_path.parent.mkdir(parents=True, exist_ok=True)

    qt = quantizer.Quantizer(str(input_path))
    qt.load_quantization_recipe(recipe.static_wi8_ai8())
    if not qt.need_calibration:
        raise RuntimeError("static_wi8_ai8 recipe unexpectedly does not need calibration")
    calibration_result = qt.calibrate(data, num_threads=args.threads)
    if not calibration_result:
        raise RuntimeError("Quantizer returned an empty calibration result")
    quant_result = qt.quantize(
        calibration_result,
        serialize_to_path=str(output_path),
        enable_progress_report=True,
    )
    if not output_path.is_file() or output_path.stat().st_size == 0:
        raise RuntimeError("Quantizer did not produce a non-empty static INT8 artifact")

    source_signatures = _signature_info(input_path)
    output_signatures = _signature_info(output_path)
    if not _signature_contract_equal(source_signatures, output_signatures):
        raise RuntimeError("Static INT8 artifact changed signature names or tensor shapes")
    audit = _flatbuffer_audit(output_path)
    if audit["int8_constant_tensors"] <= 0:
        raise RuntimeError("Static INT8 audit found no INT8/UINT8 constant tensors")
    coverage = audit["internal_int8_operator_output_coverage"]
    coverage_status = "FULL_INT8_INTERNAL_OUTPUTS" if coverage == 1.0 else "PARTIAL_OR_FLOAT_FALLBACK"

    # Compare every named sample signature directly. AEQ's generic validator
    # expects a serving_default signature, which this sample intentionally does
    # not export; named-signature inputs/outputs use the artifact's affine I/O
    # scales below. This is graph sanity, not transcript accuracy.
    output_metrics = _validate_named_signatures(
        input_path, output_path, data, args.threads
    )

    manifest = {
        "status": coverage_status,
        "quantization": "static_wi8_ai8; calibrated weights and activations",
        "ai_edge_quantizer_version": _distribution_version("ai-edge-quantizer"),
        "source_sha256": _sha256(input_path),
        "source_bytes": input_path.stat().st_size,
        "output_sha256": _sha256(output_path),
        "output_bytes": output_path.stat().st_size,
        "output": str(output_path.relative_to(ROOT)),
        "calibration": calibration_summary,
        "flatbuffer_audit": audit,
        "cpu_signature_output_mse": output_metrics,
        "numeric_validation": "completed by named-signature CPU inference with explicit external I/O quantization; not transcript accuracy",
        "quantizer_recipe": quant_result.recipe,
    }
    sidecar = output_path.with_suffix(".quantization.json")
    sidecar.write_text(json.dumps(manifest, indent=2, default=str) + "\n", encoding="utf-8")
    return manifest


def _distribution_version(package_name: str) -> str:
    try:
        from importlib.metadata import version

        return version(package_name)
    except Exception:
        return "unknown"


def _add_common_artifact_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--input", required=True, help="FP32 .tflite under models/")
    parser.add_argument("--output", required=True, help="New .tflite artifact under models/")
    parser.add_argument("--overwrite", action="store_true", help="Allow replacing the output artifact")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    fp16_parser = subparsers.add_parser(
        "fp16", help="Apply FLOAT_CASTING to weights; keep float compute"
    )
    _add_common_artifact_args(fp16_parser)

    int8_parser = subparsers.add_parser(
        "int8", help="Calibrate and apply static weights+activations INT8"
    )
    _add_common_artifact_args(int8_parser)
    int8_parser.add_argument("--model", choices=MODEL_SPECS, required=True)
    int8_parser.add_argument(
        "--calibration-audio",
        action="append",
        required=True,
        help="Repeat for each local WAV under test_data/ or the private /tmp calibration directory",
    )
    int8_parser.add_argument("--input-sec", type=float, default=5.0)
    int8_parser.add_argument("--threads", type=int, default=2)
    int8_parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="Validate sample-produced tensors against every exact model signature; do not quantize",
    )

    args = parser.parse_args(argv)
    try:
        input_path = _repo_path(args.input, MODELS_ROOT, must_exist=True)
        output_path = _repo_path(args.output, MODELS_ROOT, must_exist=False)
        if input_path == output_path:
            raise ValueError("Input and output must be different paths; FP32 is preserved")
        if args.command == "fp16":
            result = _quantize_fp16(input_path, output_path, args.overwrite)
        else:
            if args.input_sec <= 0 or args.threads <= 0:
                raise ValueError("--input-sec and --threads must be positive")
            result = _quantize_int8(args, input_path, output_path)
        print(json.dumps(result, indent=2, default=str))
        if result.get("status") == "PARTIAL_OR_FLOAT_FALLBACK":
            print(
                "Static INT8 artifact retained for inspection, but operator "
                "coverage includes floating-point fallback; it is not a full-INT8 candidate.",
                file=sys.stderr,
            )
            return 2
        return 0
    except Exception as error:  # Surface conversion/validation failures to the caller.
        print(f"ERROR: {type(error).__name__}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
