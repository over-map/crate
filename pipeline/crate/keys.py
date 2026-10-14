"""Musical keys and the Camelot wheel.

Camelot numbers 1-12 go round the circle of fifths; ``A`` is minor (inner ring), ``B`` is major (outer ring).
"""

from __future__ import annotations

import re

_SHARP = {"Db": "C#", "Eb": "D#", "Gb": "F#", "Ab": "G#", "Bb": "A#", "Cb": "B", "Fb": "E", "E#": "F", "B#": "C"}

# Camelot number for each (tonic, scale), tonics spelled with sharps.
_MINOR = {"G#": 1, "D#": 2, "A#": 3, "F": 4, "C": 5, "G": 6, "D": 7, "A": 8, "E": 9, "B": 10, "F#": 11, "C#": 12}
_MAJOR = {"B": 1, "F#": 2, "C#": 3, "G#": 4, "D#": 5, "A#": 6, "F": 7, "C": 8, "G": 9, "D": 10, "A": 11, "E": 12}

_NAMES = {
    "1A": "A♭ minor",
    "2A": "E♭ minor",
    "3A": "B♭ minor",
    "4A": "F minor",
    "5A": "C minor",
    "6A": "G minor",
    "7A": "D minor",
    "8A": "A minor",
    "9A": "E minor",
    "10A": "B minor",
    "11A": "F♯ minor",
    "12A": "D♭ minor",
    "1B": "B major",
    "2B": "F♯ major",
    "3B": "D♭ major",
    "4B": "A♭ major",
    "5B": "E♭ major",
    "6B": "B♭ major",
    "7B": "F major",
    "8B": "C major",
    "9B": "G major",
    "10B": "D major",
    "11B": "A major",
    "12B": "E major",
}

_CAMELOT_RE = re.compile(r"^(1[0-2]|[1-9])([AB])$")


def is_camelot(code: str) -> bool:
    """True for a valid Camelot code such as ``8A`` or ``12B``."""
    return bool(_CAMELOT_RE.match(code or ""))


def to_camelot(tonic: str, scale: str) -> str:
    """Convert a key like ``("A", "minor")`` to Camelot (``"8A"``). Accepts sharps or flats."""
    t = tonic.strip()
    t = t[:1].upper() + t[1:].replace("♯", "#").replace("♭", "b")
    t = _SHARP.get(t, t)
    s = scale.strip().lower()
    table = _MINOR if s.startswith("min") else _MAJOR if s.startswith("maj") else None
    if table is None or t not in table:
        raise ValueError(f"unknown key {tonic!r} {scale!r}")
    return f"{table[t]}{'A' if table is _MINOR else 'B'}"


def name(code: str) -> str:
    """Human name for a Camelot code, e.g. ``"8A"`` -> ``"A minor"``."""
    return _NAMES[code]


def compatible(code: str) -> list[str]:
    """The key itself, its two neighbours on the same ring, and the relative key on the other ring."""
    m = _CAMELOT_RE.match(code or "")
    if not m:
        raise ValueError(f"not a Camelot code: {code!r}")
    n, letter = int(m.group(1)), m.group(2)
    down = (n - 2) % 12 + 1
    up = n % 12 + 1
    other = "B" if letter == "A" else "A"
    return [code, f"{down}{letter}", f"{up}{letter}", f"{n}{other}"]
