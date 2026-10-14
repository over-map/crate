"""BPM resolution across sources and octaves (build spec §6.5)."""

from __future__ import annotations

from dataclasses import dataclass

AGREE = 0.02  # two estimates agree within ±2 %
WEIGHTS = {"tempocnn": 1.0, "rhythm": 0.8, "deezer": 0.6}
OCTAVE_CREDIT = 0.35  # a source whose ×2 or ÷2 agrees counts a little
# Prior on the tempo itself: 80–180 is most likely, 60–80 and 180–200 plausible, anything else rare.
DANCE_HIGH = 0.95  # Discogs-EffNet danceability sits near 1 for most dance music; 0.6 flipped Teardrop to 154
SLOW_OCTAVE_PENALTY = 0.6  # danceable tracks estimated below 90 BPM lean to the faster octave
MIN_BPM, MAX_BPM = 40.0, 250.0


@dataclass(frozen=True)
class BpmResult:
    bpm: float
    confidence: float
    candidates: list[float]


def _agrees(a: float, b: float) -> bool:
    return abs(a - b) <= AGREE * b


def _round(v: float) -> float:
    return round(v * 2) / 2


def prior(c: float) -> float:
    if 80 <= c <= 180:
        return 1.0
    if 60 <= c < 80 or 180 < c <= 200:
        return 0.7
    return 0.3


def resolve(
    tempocnn: float | None = None,
    rhythm: float | None = None,
    deezer: float | None = None,
    danceability: float | None = None,
) -> BpmResult | None:
    """Pick the most plausible BPM from the estimates. Returns None if there are none."""
    sources = {k: v for k, v in (("tempocnn", tempocnn), ("rhythm", rhythm), ("deezer", deezer)) if v and v > 0}
    if not sources:
        return None

    cands: list[float] = []
    for v in sources.values():
        for c in (v, v * 2, v / 2):
            if MIN_BPM <= c <= MAX_BPM and not any(_agrees(c, x) for x in cands):
                cands.append(c)

    def score(c: float) -> float:
        s = 0.0
        for name, v in sources.items():
            if _agrees(v, c):
                s += WEIGHTS[name]
            elif _agrees(v * 2, c) or _agrees(v / 2, c):
                s += WEIGHTS[name] * OCTAVE_CREDIT
        s *= prior(c)
        if danceability is not None and danceability >= DANCE_HIGH and c < 90:
            s *= SLOW_OCTAVE_PENALTY
        return s

    scored = sorted(((score(c), c) for c in cands), key=lambda x: (-x[0], x[1]))
    total = sum(s for s, _ in scored)
    best_score, best = scored[0]
    bpm = _round(best)
    octaves = [bpm] + [_round(x) for x in (bpm * 2, bpm / 2) if MIN_BPM <= x <= MAX_BPM]
    return BpmResult(bpm=bpm, confidence=round(best_score / total, 3) if total else 0.0, candidates=octaves)
