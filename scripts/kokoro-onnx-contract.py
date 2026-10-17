#!/usr/bin/env python3
"""Verify the pinned Kokoro ONNX graph contract and stochastic repeat behavior."""

from __future__ import annotations

import hashlib
import json
import tempfile
import urllib.request
from pathlib import Path

try:
    import numpy as np
    import onnxruntime as ort
except ModuleNotFoundError as error:
    raise SystemExit(
        f"Missing {error.name}; run this check with the prepared ~/titl-venv"
    ) from error


REVISION = "115a6c0469632a84fbcc6c8ed0ad7b1e06154b92"
BASE_URL = f"https://huggingface.co/MithrilMan/teacher-in-the-loop/resolve/{REVISION}"
MODEL_URL = f"{BASE_URL}/af_heart/model_int8.onnx"
VOCAB_URL = f"{BASE_URL}/vocab.json"
MODEL_SIZE = 9_168_506
MODEL_SHA256 = "f3daed94c8fe1f7a90f14cd7fc8ead55c99a8e57dd0aac669e376882483222c7"
VOCAB_SIZE = 1_147
VOCAB_SHA256 = "c985ed32b1007e0d52c8e86dd3f54e3ec62b74d14212bb5718348f7e9d734acf"
SAMPLE_RATE_HZ = 24_000


def download(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "VoiceChat-Kokoro-contract-check/1"})
    with urllib.request.urlopen(request, timeout=60) as response, destination.open("wb") as output:
        while chunk := response.read(64 * 1024):
            output.write(chunk)


def verify(path: Path, expected_size: int, expected_sha256: str) -> None:
    content = path.read_bytes()
    actual_sha256 = hashlib.sha256(content).hexdigest()
    if len(content) != expected_size or actual_sha256 != expected_sha256:
        raise SystemExit(
            f"identity mismatch for {path.name}: size={len(content)}, sha256={actual_sha256}"
        )
    print(f"verified {path.name}: {len(content)} bytes, sha256={actual_sha256}")


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="kokoro-onnx-contract-", dir="/tmp") as temporary:
        directory = Path(temporary)
        graph_path = directory / "model_int8.onnx"
        vocab_path = directory / "vocab.json"
        download(MODEL_URL, graph_path)
        download(VOCAB_URL, vocab_path)
        verify(graph_path, MODEL_SIZE, MODEL_SHA256)
        verify(vocab_path, VOCAB_SIZE, VOCAB_SHA256)

        vocab = json.loads(vocab_path.read_text(encoding="utf-8"))
        symbols = [symbol for symbol in ("h", "ə", "ˈ", "l", "o", "ʊ") if symbol in vocab]
        if len(symbols) < 3:
            raise SystemExit(f"vocab.json lacks enough hand-built test symbols: {symbols!r}")
        ids = [0, *(int(vocab[symbol]) for symbol in symbols), 0]
        input_ids = np.asarray([ids], dtype=np.int64)
        speed = np.asarray([1.0], dtype=np.float32)

        session = ort.InferenceSession(str(graph_path), providers=["CPUExecutionProvider"])
        print(f"onnxruntime: {ort.__version__}; numpy: {np.__version__}")
        print("inputs:")
        for value in session.get_inputs():
            print(f"  {value.name}: {value.type} {value.shape}")
        print("outputs:")
        for value in session.get_outputs():
            print(f"  {value.name}: {value.type} {value.shape}")
        print(f"phoneme symbols: {symbols!r}")
        print(f"input ids: {ids!r}")

        first = session.run(["waveform"], {"input_ids": input_ids, "speed": speed})[0]
        second = session.run(["waveform"], {"input_ids": input_ids, "speed": speed})[0]
        for waveform in (first, second):
            if (
                waveform.dtype != np.float32
                or waveform.ndim != 2
                or waveform.shape[0] != 1
                or waveform.shape[1] == 0
            ):
                raise SystemExit(f"unexpected waveform: dtype={waveform.dtype}, shape={waveform.shape}")
        if second.shape != first.shape:
            raise SystemExit(f"repeat output shape changed: {first.shape} -> {second.shape}")

        waveform = first[0]
        difference = np.abs(first.astype(np.float64) - second.astype(np.float64))
        print(f"waveform dtype: {first.dtype}")
        print(f"waveform shape: {first.shape}")
        print(f"duration at {SAMPLE_RATE_HZ} Hz: {waveform.size / SAMPLE_RATE_HZ:.6f} seconds")
        print(f"min/max/mean: {waveform.min():.9f} / {waveform.max():.9f} / {waveform.mean():.9f}")
        print(f"non-silent (any abs(sample) > 1e-8): {bool(np.any(np.abs(waveform) > 1e-8))}")
        print(f"repeat bit-identical: {bool(np.array_equal(first, second))}")
        print(f"repeat max absolute delta: {difference.max():.9f}")
        print(f"repeat mean absolute delta: {difference.mean():.9f}")
        print(f"repeat RMS delta: {np.sqrt(np.mean(difference**2)):.9f}")


if __name__ == "__main__":
    main()
