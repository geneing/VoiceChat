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

"""Compatibility shim for the pinned speech sample under transformers 5.18.0.

The downloaded sample is never edited. Instead this tracked entry point patches
only the version-specific Parakeet CTC encoder forward and then runs the
upstream converter's flag-based ``main`` unchanged.

Why the patch is needed
-----------------------
The pinned sample's ``ParakeetCTCEncoder.forward`` predates the current
``transformers.models.parakeet.modeling_parakeet.ParakeetEncoderCTCHead``. That
head is now an ``nn.Conv1d`` subclass whose own forward already transposes to
``(batch, hidden, time)`` and back, so it expects ``(batch, time, hidden)``.
The sample still wraps the call in ``hidden_states.transpose(1, 2)`` and the
result in ``.transpose(1, 2)``, which double-transposes and raises
``RuntimeError: ... expected input[..., 163, 1024] to have 1024 channels``.
Reproduced with transformers 5.18.0 on 2026-10-02; the failure was recorded
before this shim existed.

The patch mirrors the sample's simplified forward verbatim and only drops the
now-redundant transposes. It is intentionally limited to the CTC encoder; the
TDT encoder/decoder path is unaffected and is routed through the same entry
point only so both Parakeet families share one tracked compatibility boundary.
"""

from __future__ import annotations

from samples.litert.speech_recognition.convert import convert_to_tflite
from samples.litert.speech_recognition.convert import parakeet_ctc


def _compat_parakeet_ctc_forward(self, input_features):
    """Sample ParakeetCTCEncoder.forward with the transformers 5.18 head contract."""
    hidden_states = self._encoder.subsampling(input_features)
    hidden_states = hidden_states * self._encoder.input_scale
    position_embeddings = self._encoder.encode_positions(hidden_states)

    for encoder_layer in self._encoder.layers:
        hidden_states = encoder_layer(
            hidden_states, position_embeddings=position_embeddings
        )

    # ParakeetEncoderCTCHead expects (batch, time, hidden) and returns
    # (batch, time, vocab); do not transpose here.
    logits = self._ctc_head(hidden_states)
    return (logits,)


def patch() -> None:
    """Applies the compatibility patch in-process."""
    parakeet_ctc.ParakeetCTCEncoder.forward = _compat_parakeet_ctc_forward


def main() -> None:
    patch()
    convert_to_tflite.app.run(convert_to_tflite.main)


if __name__ == "__main__":
    main()
