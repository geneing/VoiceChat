#!/usr/bin/env python3
"""Host log-Mel feature extraction for the LiteRT ASR spike.

Reproduces the pinned sample's Parakeet front end (MelSpectroProcessor with
jlibrosa/librosa defaults) on the host so the on-device comparison test consumes
a frozen `.f32` window instead of running preprocessing. This mirrors the recipe
in the sample's `MelSpectroProcessor.kt`:

  preemphasis 0.97 -> mel spectrogram (slaney norm, ln) -> per-mel mean/std
  normalization over valid frames -> transpose for Parakeet (nFrames x nMels)

The output is one little-endian float32 array per clip under an ignored models/
path. Nothing here is committed as data.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import numpy as np


def decode_pcm(path: Path, sample_rate: int) -> np.ndarray:
    """Decode any container to mono float32 at sample_rate via ffmpeg."""
    result = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path),
         "-f", "f32le", "-ac", "1", "-ar", str(sample_rate), "-"],
        check=True, stdout=subprocess.PIPE,
    )
    return np.frombuffer(result.stdout, dtype=np.float32).copy()


def log_mel(
    audio: np.ndarray,
    sample_rate: int,
    n_fft: int,
    n_mels: int,
    hop_length: int,
    transpose: bool,
    n_frames: int,
) -> np.ndarray:
    import librosa

    preemphasized = np.empty_like(audio)
    preemphasized[0] = audio[0]
    preemphasized[1:] = audio[1:] - 0.97 * audio[:-1]

    mel = librosa.feature.melspectrogram(
        y=preemphasized, sr=sample_rate, n_fft=n_fft, hop_length=hop_length,
        n_mels=n_mels, power=2.0, norm="slaney",
    )
    mel = np.log(mel + 2.0 ** -24)

    valid_frames = len(audio) // hop_length
    subset = mel[:, :valid_frames]
    means = subset.mean(axis=1, keepdims=True)
    stds = subset.std(axis=1, ddof=1, keepdims=True) + 1e-5
    normalized = (subset - means) / stds

    padded = np.zeros((n_mels, n_frames), dtype=np.float32)
    usable = min(n_frames, normalized.shape[1])
    padded[:, :usable] = normalized[:, :usable]
    if transpose:
        padded = padded.T
    return np.ascontiguousarray(padded, dtype=np.float32)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--family", choices=["parakeet-ctc", "parakeet-tdt"], required=True)
    parser.add_argument("--input", required=True, help="source audio file")
    parser.add_argument("--output", required=True, help="output .f32 path")
    parser.add_argument("--sample-rate", type=int, default=16_000)
    parser.add_argument("--n-fft", type=int, default=512)
    parser.add_argument("--n-mels", type=int, default=80)
    parser.add_argument("--n-frames", type=int, default=500)
    parser.add_argument("--hop-length", type=int, default=160)
    parser.add_argument("--seconds", type=float, default=5.0)
    args = parser.parse_args()

    audio = decode_pcm(Path(args.input), args.sample_rate)
    window_samples = int(args.seconds * args.sample_rate)
    if len(audio) < window_samples:
        audio = np.pad(audio, (0, window_samples - len(audio)))
    audio = audio[:window_samples]

    # Parakeet CTC in the sample uses the default (mel-major) layout; the
    # metadata's `transpose: true` is only set for the CTC front end, so honor
    # the family mapping used by the converted artifacts.
    transpose = args.family == "parakeet-ctc"
    features = log_mel(
        audio, args.sample_rate, args.n_fft, args.n_mels, args.hop_length,
        transpose, args.n_frames,
    )
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_bytes(features.tobytes(order="C"))
    flat = features.reshape(-1)
    print(f"{args.output}\tshape={features.shape}\telements={flat.size}\t"
          f"min={flat.min():.5f}\tmax={flat.max():.5f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
