#!/usr/bin/env python3
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

"""Dumps the pinned sample's exact model input features as raw little-endian f32.

The sample converters build their graphs from ``TransformersProcessor`` outputs
(log-Mel for Whisper and Parakeet, raw PCM for Moonshine). The Android sample
reproduces those features with a jlibrosa front-end that is not a dependency of
this project. For a device graph-inference benchmark we instead extract the
features on the host with the sample's own processor, freeze them to a file, and
push that file to the device. This keeps preprocessing identical to conversion
and avoids reimplementing or guessing the mel front-end.

This is a host preprocessing step only: the dumped file is a model input, not
audio, and it is stored under the Git-ignored ``models/`` directory.

Usage:
  litert_asr_dump_features.py --model-id ID --audio PATH --input-sec SEC
      --output PATH
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import sys

import librosa

from samples.litert.speech_recognition.convert import asr_model


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--audio", required=True)
    parser.add_argument("--input-sec", type=float, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    audio = Path(os.path.abspath(args.audio))
    output = Path(os.path.abspath(args.output))

    try:
        audio.relative_to(repo_root / "test_data")
    except ValueError:
        print(f"Audio must be inside test_data/: {audio}", file=sys.stderr)
        return 2
    try:
        output.relative_to(repo_root / "models")
    except ValueError:
        print(f"Output must be inside models/: {output}", file=sys.stderr)
        return 3
    if not audio.is_file():
        print(f"Audio not found: {audio}", file=sys.stderr)
        return 4

    processor = asr_model.TransformersProcessor(args.model_id)
    sampling_rate = processor.get_sampling_rate()
    duration = args.input_sec - 0.01 if "parakeet" in args.model_id else args.input_sec
    sample, _ = librosa.load(str(audio), sr=sampling_rate, duration=duration)
    inputs = processor.process(sample)
    features = inputs["input_features"]
    array = features.detach().cpu().numpy().astype("<f4", copy=False)

    output.parent.mkdir(parents=True, exist_ok=True)
    array.tofile(output)
    print(
        {
            "modelId": args.model_id,
            "samplingRateHz": sampling_rate,
            "inputSec": args.input_sec,
            "audioSha256": _sha256(audio),
            "featureShape": list(array.shape),
            "featureSha256": _sha256(output),
            "outputBytes": output.stat().st_size,
        }
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
