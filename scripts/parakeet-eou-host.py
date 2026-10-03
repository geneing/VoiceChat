"""
Parakeet-EOU-120M streaming decode + end-of-utterance, host reference.

Mirrors the pinned reference implementation (soniqo/speech-swift
`ParakeetStreamingASR`: StreamingSession + StreamingMelPreprocessor +
RNNTGreedyDecoder). Used to measure accuracy/latency of the ONNX INT8 bundle on
the host and to cross-check the Kotlin port in the app. The host numbers are a
sanity check, not a device claim.
"""
import argparse
import json
import os
import struct
import time
import wave

import numpy as np
import onnxruntime as ort

LOG_GUARD = 5.960464477539063e-08  # 2^-24
N_BINS = 257
REFLECT_PAD = 256


class Config:
    def __init__(self, path):
        with open(path) as f:
            c = json.load(f)
        self.num_mel_bins = c["numMelBins"]
        self.sample_rate = c["sampleRate"]
        self.n_fft = c["nFFT"]
        self.hop = c["hopLength"]
        self.win = c["winLength"]
        self.pre_emph = c["preEmphasis"]
        self.enc_hidden = c["encoderHidden"]
        self.enc_layers = c["encoderLayers"]
        self.sub = c["subsamplingFactor"]
        self.att_ctx = c["attentionContext"]
        self.conv_cache = c["convCacheSize"]
        self.dec_hidden = c["decoderHidden"]
        self.dec_layers = c["decoderLayers"]
        self.vocab_size = c["vocabSize"]
        self.blank = c["blankTokenId"]
        self.eou = c["eouTokenId"]
        self.eob = c["eobTokenId"]
        s = c["streaming"]
        self.chunk_ms = s["chunkMs"]
        self.mel_frames = s["melFrames"]
        self.pre_cache = s["preCacheSize"]
        self.output_frames = s["outputFrames"]


def hann(n):
    # periodic=False: denominator n-1
    return 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(n) / (n - 1)))


def mel_filterbank(n_mels, n_bins, sample_rate, padded_fft):
    f_max = sample_rate / 2.0
    min_log_hz = 1000.0
    min_log_mel = 15.0
    logstep = np.log(6.4) / 27.0

    def hz2mel(hz):
        return np.where(hz < min_log_hz, 3.0 * hz / 200.0, min_log_mel + np.log(np.maximum(hz, min_log_hz) / min_log_hz) / logstep)

    def mel2hz(m):
        return np.where(m < min_log_mel, 200.0 * m / 3.0, min_log_hz * np.exp((m - min_log_mel) * logstep))

    fft_freqs = np.arange(n_bins) * sample_rate / padded_fft
    mel_pts = np.linspace(hz2mel(0.0), hz2mel(f_max), n_mels + 2)
    freqs = mel2hz(mel_pts)
    fb = np.zeros((n_bins, n_mels))
    for mel in range(n_mels):
        lo, ce, hi = freqs[mel], freqs[mel + 1], freqs[mel + 2]
        fb[:, mel] = np.maximum(0.0, np.minimum((fft_freqs - lo) / (ce - lo), (hi - fft_freqs) / (hi - ce)))
        fb[:, mel] *= 2.0 / (hi - lo)
    return fb  # [n_bins, n_mels]


class MelExtractor:
    """Raw log-mel, zero center padding, no normalization (NeMo EOU contract)."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.window = hann(cfg.win)
        self.filterbank = mel_filterbank(cfg.num_mel_bins, N_BINS, cfg.sample_rate, cfg.n_fft)
        self.win_offset = (cfg.n_fft - cfg.win) // 2

    def extract_raw(self, audio):
        cfg = self.cfg
        audio = np.asarray(audio, dtype=np.float32)
        if audio.size == 0:
            return np.zeros((cfg.num_mel_bins, 0), dtype=np.float32)
        emphasized = np.empty_like(audio)
        emphasized[0] = audio[0]
        emphasized[1:] = audio[1:] - cfg.pre_emph * audio[:-1]
        padded = np.zeros(REFLECT_PAD + emphasized.size + REFLECT_PAD, dtype=np.float32)
        padded[REFLECT_PAD:REFLECT_PAD + emphasized.size] = emphasized
        n_frames = (padded.size - cfg.n_fft) // cfg.hop + 1
        mel_len = audio.size // cfg.hop
        frames = np.zeros((n_frames, cfg.n_fft), dtype=np.float32)
        for f in range(n_frames):
            start = f * cfg.hop + self.win_offset
            frames[f, self.win_offset:self.win_offset + cfg.win] = padded[start:start + cfg.win] * self.window
        spectrum = np.fft.rfft(frames, axis=1)
        power = (spectrum.real ** 2 + spectrum.imag ** 2)
        mel = power @ self.filterbank  # [n_frames, n_mels]
        mel = np.log(mel + LOG_GUARD)
        return np.ascontiguousarray(mel.T)  # [n_mels, n_frames]


class ParakeetEou:
    def __init__(self, model_dir, threads=4):
        cfg = Config(os.path.join(model_dir, "config.json"))
        self.cfg = cfg
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.encoder = ort.InferenceSession(os.path.join(model_dir, "parakeet-eou-encoder.onnx"), so)
        self.decoder = ort.InferenceSession(os.path.join(model_dir, "parakeet-eou-decoder.onnx"), so)
        self.joint = ort.InferenceSession(os.path.join(model_dir, "parakeet-eou-joint.onnx"), so)
        with open(os.path.join(model_dir, "vocab.json")) as f:
            raw = json.load(f)
        self.vocab = {int(k): v for k, v in raw.items()}
        self.mel = MelExtractor(cfg)
        self.reset()

    def reset(self):
        cfg = self.cfg
        self.pre_cache = np.zeros((1, cfg.num_mel_bins, cfg.pre_cache), dtype=np.float32)
        self.cache_ch = np.zeros((cfg.enc_layers, 1, cfg.att_ctx, cfg.enc_hidden), dtype=np.float32)
        self.cache_time = np.zeros((cfg.enc_layers, 1, cfg.enc_hidden, cfg.conv_cache), dtype=np.float32)
        self.cache_len = np.zeros((1,), dtype=np.int64)
        self.h = np.zeros((1, 1, cfg.dec_hidden), dtype=np.float32)
        self.c = np.zeros((1, 1, cfg.dec_hidden), dtype=np.float32)
        tok = np.array([[cfg.blank]], dtype=np.int64)
        dec = self.decoder.run(None, {"token": tok, "h": self.h, "c": self.c})
        self.dec_out = dec[0].astype(np.float32)
        self.h = dec[1].astype(np.float32)
        self.c = dec[2].astype(np.float32)

    def _run_encoder(self, chunk_mel):
        cfg = self.cfg
        out = self.encoder.run(None, {
            "audio_signal": chunk_mel[np.newaxis, :, :].astype(np.float32),
            "audio_length": np.array([cfg.mel_frames], dtype=np.int64),
            "pre_cache": self.pre_cache,
            "cache_last_channel": self.cache_ch,
            "cache_last_time": self.cache_time,
            "cache_last_channel_len": self.cache_len,
        })
        encoded, enc_len, new_pre, new_ch, new_time, new_len = out
        self.pre_cache = new_pre
        self.cache_ch = new_ch
        self.cache_time = new_time
        self.cache_len = new_len.astype(np.int64)
        return encoded[0], int(enc_len[0])  # [T, hidden]

    def _decode_frames(self, encoded, count):
        cfg = self.cfg
        tokens, log_probs, eou = [], [], False
        for i in range(count):
            frame = encoded[i][None, None, :].astype(np.float32)  # [1,1,512]
            for _ in range(10):
                logits = self.joint.run(None, {"encoder_output": frame, "decoder_output": self.dec_out})[0][0, 0]
                tid = int(np.argmax(logits[:cfg.vocab_size + 1]))
                if tid == cfg.blank:
                    break
                if tid == cfg.eou:
                    eou = True
                    break
                tokens.append(tid)
                m = logits[:cfg.vocab_size + 1].max()
                lse = m + np.log(np.exp(logits[:cfg.vocab_size + 1] - m).sum())
                log_probs.append(float(logits[tid] - lse))
                dec = self.decoder.run(None, {
                    "token": np.array([[tid]], dtype=np.int64),
                    "h": self.h, "c": self.c,
                })
                self.dec_out = dec[0].astype(np.float32)
                self.h = dec[1].astype(np.float32)
                self.c = dec[2].astype(np.float32)
            if eou:
                break
        return tokens, log_probs, eou

    def transcribe_streaming(self, audio, debounce_ms=1280, collect=None):
        """Whole-file streaming pass; returns (text, eou_fired, per-utterance tokens)."""
        cfg = self.cfg
        self.reset()
        samples_per_chunk = cfg.mel_frames * cfg.hop
        shift_samples = cfg.output_frames * cfg.sub * cfg.hop
        buf = np.asarray(audio, dtype=np.float32)
        pos = 0
        all_tokens = []
        log_probs = []
        utterance_text = []
        eou_fired = False
        eou_first_at = None
        processed = 0
        eou_offset = 0
        seg_text = ""
        while pos + samples_per_chunk <= buf.size:
            chunk = buf[pos:pos + samples_per_chunk]
            pos += min(shift_samples, buf.size - pos) if False else shift_samples
            mel = self.mel.extract_raw(chunk)
            if mel.shape[1] > cfg.mel_frames:
                mel = mel[:, :cfg.mel_frames]
            elif mel.shape[1] < cfg.mel_frames:
                mel = np.pad(mel, ((0, 0), (0, cfg.mel_frames - mel.shape[1])))
            t0 = time.perf_counter()
            encoded, enc_len = self._run_encoder(mel)
            toks, lps, eou = self._decode_frames(encoded, min(cfg.output_frames, min(enc_len, encoded.shape[0])))
            if collect is not None:
                collect.append(time.perf_counter() - t0)
            all_tokens.extend(toks)
            log_probs.extend(lps)
            processed += chunk.size
            if eou:
                if not toks:
                    if eou_first_at is None:
                        eou_first_at = processed
                else:
                    eou_first_at = None
                if eou_first_at is not None and ((processed - eou_first_at) * 1000 // cfg.sample_rate) >= debounce_ms:
                    eou_fired = True
            else:
                eou_first_at = None
            text = self.decode_tokens(all_tokens[eou_offset:])
            if eou_fired:
                if text:
                    utterance_text.append(text)
                eou_offset = len(all_tokens)
                seg_text = ""
                eou_fired = False
                eou_first_at = None
            else:
                seg_text = text
        return seg_text, utterance_text, all_tokens, log_probs, processed

    def decode_tokens(self, ids):
        pieces = "".join(self.vocab.get(i, "") for i in ids)
        return pieces.replace("\u2581", " ").strip()

    def transcribe_full(self, audio):
        """Single-chunk style full-file decode (no streaming chunks)."""
        cfg = self.cfg
        self.reset()
        mel = self.mel.extract_raw(audio)
        # pad to a multiple of melFrames so the encoder sees whole 640 ms chunks
        rem = mel.shape[1] % cfg.mel_frames
        if rem:
            mel = np.pad(mel, ((0, 0), (0, cfg.mel_frames - rem)))
        tokens = []
        for start in range(0, mel.shape[1], cfg.mel_frames):
            encoded, enc_len = self._run_encoder(mel[:, start:start + cfg.mel_frames])
            toks, _, _ = self._decode_frames(encoded, min(cfg.output_frames * cfg.sub, min(enc_len, encoded.shape[0])))
            tokens.extend(toks)
        return self.decode_tokens(tokens)


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2, path
        rate = w.getframerate()
        ch = w.getnchannels()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
        if ch > 1:
            data = data.reshape(-1, ch).mean(axis=1)
    assert rate == 16000, f"{path}: {rate} Hz"
    return data


def wer(ref, hyp):
    r = ref.lower().split()
    h = hyp.lower().split()
    d = np.zeros((len(r) + 1, len(h) + 1), dtype=np.int32)
    d[:, 0] = np.arange(len(r) + 1)
    d[0, :] = np.arange(len(h) + 1)
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + (r[i - 1] != h[j - 1]))
    return d[len(r), len(h)] / max(len(r), 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="models/parakeet-eou")
    ap.add_argument("--data", default="/mnt/i/Android_Projects/VoiceChat-parakeet-eou/test_data/litert-asr-wer")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--debounce-ms", type=int, default=1280)
    args = ap.parse_args()

    model = ParakeetEou(args.model, args.threads)
    clips = sorted(f for f in os.listdir(args.data) if f.endswith(".wav"))
    print(f"clips={len(clips)} threads={args.threads}")
    print(f"{'clip':30s} {'mode':6s} {'dur_s':>6s} {'wall_s':>7s} {'rtf':>6s} {'eou':>4s} {'wer':>7s}")
    totals = {"full": [], "stream": []}
    text_rows = []
    for clip in clips:
        cid = clip[:-4]
        ref_path = os.path.join(args.data, f"{cid}.reference.txt")
        if not os.path.exists(ref_path):
            continue
        with open(ref_path) as f:
            ref = f.read().strip()
        audio = read_wav(os.path.join(args.data, clip))
        dur = audio.size / 16000.0
        for mode in ("full", "stream"):
            model.reset()
            if mode == "full":
                t0 = time.perf_counter()
                hyp = model.transcribe_full(audio)
                wall = time.perf_counter() - t0
                eou = ""
            else:
                t0 = time.perf_counter()
                text, utts, _, _, _ = model.transcribe_streaming(audio, args.debounce_ms)
                wall = time.perf_counter() - t0
                hyp = " ".join(utts) if utts else text
                eou = str(len(utts))
            w = wer(ref, hyp)
            totals[mode].append(w)
            print(f"{cid:30s} {mode:6s} {dur:6.2f} {wall:7.2f} {wall / dur:6.2f} {eou:>4s} {w:7.3f}")
            text_rows.append((cid, mode, hyp))
    for mode, ws in totals.items():
        if ws:
            print(f"{mode:6s} mean WER {np.mean(ws):.3f} over {len(ws)} clips")
    with open("build/parakeet-eou-host-hypotheses.txt", "w") as f:
        for cid, mode, hyp in text_rows:
            f.write(f"{cid}\t{mode}\t{hyp}\n")
    print(f"(synthetic/reference WAVs only: reference is a local Whisper pseudo-label)")


if __name__ == "__main__":
    main()
