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

        measure_inference(session, vocab)


def measure_inference(session, vocab: dict[str, int]) -> None:
    """Time one graph call per assistant-shaped sentence.

    This is a **host** measurement of the model on this machine's CPU under Python
    ONNX Runtime, not the app's device latency: it excludes the Android runtime
    (the app pins 1.29.0), the Kotlin phonemizer, and the audio device. It exists
    so a device number has something honest to be compared against, and so a
    graph whose cost changed is visible.
    """
    import time

    sentences = [
        "The weather in Yosemite will be clear this weekend.",
        "Take the Mist Trail to the top of Vernal Fall, then continue to Nevada Fall.",
        "Start early, because the parking lot fills by seven thirty in the morning on a summer weekend.",
    ]
    phonemizer = None
    try:
        from titl.phonemes import Phonemizer

        phonemizer = Phonemizer("en")
    except Exception as error:  # noqa: BLE001 - the check runs without the G2P when absent
        print(f"phonemizer unavailable ({error.__class__.__name__}); using repeated hand-built ids")

    print()
    print(f"inference timing (CPU, {session.get_providers()[0]}, host machine):")
    for sentence in sentences:
        if phonemizer is not None:
            phonemes, _ = phonemizer._g2p(sentence)  # noqa: SLF001 - the same call titl makes
            phonemes = phonemes.strip()
            ids = [0, *(int(vocab[c]) for c in phonemes if c in vocab), 0]
        else:
            ids = [0, *(int(vocab[s]) for s in ("h", "ə", "ˈ", "l", "o", "ʊ") * 7), 0]
        input_ids = np.asarray([ids], dtype=np.int64)
        speed = np.asarray([1.0], dtype=np.float32)
        session.run(["waveform"], {"input_ids": input_ids, "speed": speed})
        samples = 0
        durations = []
        for _ in range(REPEATS):
            started = time.perf_counter()
            samples = int(
                session.run(["waveform"], {"input_ids": input_ids, "speed": speed})[0].shape[1]
            )
            durations.append((time.perf_counter() - started) * 1000.0)
        durations.sort()
        median_ms = durations[len(durations) // 2]
        audio_seconds = samples / SAMPLE_RATE_HZ
        realtime = (audio_seconds * 1000.0 / median_ms) if median_ms > 0 else float("inf")
        print(
            f"  tokens={len(ids)} audio={audio_seconds:.3f}s "
            f"median={median_ms:.1f}ms min={durations[0]:.1f}ms max={durations[-1]:.1f}ms "
            f"realtime={realtime:.1f}x"
        )


REPEATS = 5


if __name__ == "__main__":
    main()
