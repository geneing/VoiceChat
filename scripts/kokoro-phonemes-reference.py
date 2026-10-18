#!/usr/bin/env python3
"""Freeze Misaki 0.9.4 English reference output for the Kotlin Kokoro port.

Run with ``~/titl-venv/bin/python scripts/kokoro-phonemes-reference.py`` in WSL.
The corpus is embedded and generated deterministically here. Its original
practice sentences use ordinary vocabulary and public-domain character/place
names from Lewis Carroll's *Alice's Adventures in Wonderland* (1865); no book
download or other external input is needed at run time.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from misaki import en, espeak
from titl.phonemes import MAX_PHONEMES, encode, split_phonemes


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/test/resources/kokoro/english-phonemes.json"
SENTENCE_BREAK = re.compile(r"(?<=[.!?…])\s+|\n+")

SUBJECTS = [
    "Alice", "the rabbit", "the girl", "the boy", "the queen", "the king",
    "the mouse", "the cat", "the child", "the traveler",
]
VERBS = [
    "walked", "ran", "looked", "waited", "turned", "asked", "answered",
    "watched", "followed", "returned",
]
PLACES = [
    "into the garden", "through the door", "down the road", "across the room",
    "near the river", "under the tree", "toward the house", "beside the table",
    "along the path", "around the hall",
]
THINGS = [
    "a small book", "a bright lamp", "the old clock", "a little key", "the white rabbit",
    "a cup of tea", "the wooden door", "a red flower", "the long hall", "a quiet room",
]
def corpus() -> list[str]:
    """Return the three app cases and 200 fixed, ordinary English sentences."""
    sentences = [
        "The weather in Yosemite will be clear this weekend.",
        "Take the Mist Trail to the top of Vernal Fall, then continue to Nevada Fall.",
        "Start early, because the parking lot fills by seven thirty in the morning on a summer weekend.",
    ]
    sentences.extend(f"{subject.capitalize()} {verb} {place}." for subject, verb, place in zip(
        (subject for subject in SUBJECTS for _ in VERBS),
        (verb for _ in SUBJECTS for verb in VERBS),
        (place for _ in SUBJECTS for place in PLACES),
    ))
    sentences.extend(f"{subject.capitalize()} found {thing}." for subject in SUBJECTS for thing in THINGS)
    assert len(sentences) == 203, len(sentences)
    return sentences


def make_record(text: str, g2p: en.G2P, vocab: dict[str, int]) -> dict[str, object]:
    phonemes, _ = g2p(text)
    phonemes = phonemes.strip()
    chunks = []
    for sentence in SENTENCE_BREAK.split(text.strip()):
        if sentence.strip():
            sentence_phones, _ = g2p(sentence.strip())
            chunks.extend(split_phonemes(sentence_phones.strip(), MAX_PHONEMES))
    return {
        "text": text,
        "phonemes": phonemes,
        "chunks": chunks,
        "ids": [encode(chunk, vocab) for chunk in chunks],
    }


def main() -> None:
    g2p = en.G2P(trf=False, british=False, fallback=espeak.EspeakFallback(british=False), unk="")
    import titl.phonemes as titl_phonemes

    vocab = titl_phonemes.load_vocab()
    edge_cases = [
        "There are 21 bright birds and 3 cats.",
        "In 1984, the rabbit finished 1st, 2nd, and 3rd.",
        "The total is $12.50, or €7.25 in cash.",
        "Add 50% and use A+B & C@Home.",
        "NASA and the FBI met Alice at the BBC.",
        "!!! ... — ?",
        ("The little rabbit ran through the quiet garden and looked at the old door " * 18).strip() + ".",
        "Read [Alice](/ˈæ.lɪs/) before you open the door.",
    ]
    data = {
        "reference": "Misaki en.py 0.9.4 with titl/phonemes.py; US, trf=False, eSpeak fallback",
        "corpus": [make_record(text, g2p, vocab) for text in corpus()],
        "edgeCases": [make_record(text, g2p, vocab) for text in edge_cases],
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(data['corpus'])} corpus and {len(data['edgeCases'])} edge fixtures to {OUTPUT}")


if __name__ == "__main__":
    main()
