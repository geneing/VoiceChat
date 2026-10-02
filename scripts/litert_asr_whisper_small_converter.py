# Copyright 2026 Google LLC.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Whisper Small extension using the pinned sample's model/preprocess contracts.

The upstream converter's enum exposes Whisper Tiny only. This extension keeps
its conversion flow and signature names while selecting openai/whisper-small.
"""

from __future__ import annotations

import math
import os

from absl import app
from absl import flags
from absl import logging
import librosa
import litert_torch
from litert_torch.generative.quantize import quant_attrs
from litert_torch.generative.quantize import quant_recipe
from litert_torch.quantize import quant_config

from samples.litert.speech_recognition.convert import whisper

_MODEL_ID = "openai/whisper-small"
_STATEFUL_AFTER = flags.DEFINE_integer(
    "stateful_after",
    -1,
    "If >= 0, the model runs in stateful mode after this many tokens.",
)
_INPUT_SEC = flags.DEFINE_float("input_sec", 1.0, "Input audio length in seconds.")
_SAMPLE_AUDIO = flags.DEFINE_string("sample_audio", "", "Input sample audio path.")
_OUTPUT = flags.DEFINE_string(
    "output", "models/litert-asr/whisper-small-fp32.tflite", "Output LiteRT model path."
)
_QUANT = flags.DEFINE_enum(
    "quant", "none", ["none", "drq"], "Upstream sample quantization option."
)


def _num_decode_tokens() -> int:
    num_tokens = math.ceil(_INPUT_SEC.value * 8 / 32) * 32
    if 0 <= _STATEFUL_AFTER.value < num_tokens:
        return _STATEFUL_AFTER.value if _STATEFUL_AFTER.value > 0 else 1
    return num_tokens


def _quantization_config():
    if _QUANT.value == "drq":
        layer_recipe = quant_recipe.LayerQuantRecipe(
            activation_dtype=quant_attrs.Dtype.FP32,
            weight_dtype=quant_attrs.Dtype.INT8,
            mode=quant_attrs.Mode.DYNAMIC_RANGE,
            algorithm=quant_attrs.Algorithm.MIN_MAX,
            granularity=quant_attrs.Granularity.CHANNELWISE,
        )
        return quant_config.QuantConfig(
            generative_recipe=quant_recipe.GenerativeQuantRecipe(default=layer_recipe)
        )
    if _QUANT.value == "none":
        return None
    raise app.UsageError(f"Unsupported quantization: {_QUANT.value}")


def _main(_):
    if not _SAMPLE_AUDIO.value:
        raise app.UsageError("--sample_audio is required.")
    model = whisper.Whisper(model_id=_MODEL_ID, override_transformers=True)
    processor = model.get_processor()
    sample, sample_rate = librosa.load(
        _SAMPLE_AUDIO.value,
        sr=processor.get_sampling_rate(),
        duration=_INPUT_SEC.value,
    )
    processed_audio = processor.process(sample)
    encoder_inputs = model.get_encoder_sample_input(processed_audio)
    logging.info(
        "Rate=%d, encoder input shapes=%s",
        sample_rate,
        [(tensor.size(), tensor.dtype) for tensor in encoder_inputs],
    )
    encoder = model.get_encoder()
    edge_model = litert_torch.signature("encode", encoder, encoder_inputs)
    encoder_outputs = encoder(*encoder_inputs)
    decoder = model.get_decoder()
    decoder_inputs = model.get_decoder_sample_input(
        encoder_outputs, _num_decode_tokens()
    )
    edge_model = edge_model.signature("decode", decoder, decoder_inputs)
    if _STATEFUL_AFTER.value > 1:
        decoder_inputs_one_token = model.get_decoder_sample_input(encoder_outputs, 1)
        edge_model = edge_model.signature(
            "decode_1", decoder, decoder_inputs_one_token
        )
    edge_model = edge_model.convert(
        enable_x64=False, quant_config=_quantization_config()
    )
    output_path = os.path.abspath(_OUTPUT.value)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    logging.info("Exporting Whisper Small model to %s...", output_path)
    edge_model.export(output_path)


if __name__ == "__main__":
    app.run(_main)