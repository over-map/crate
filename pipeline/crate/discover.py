"""Weekly Mix selection: taste picks from rotating families plus discovery picks (pure functions, no I/O).

Design (user decision 2026-10-12): based on taste, but *not* shaped like the library's genre split.

- **Taste (20 slots):** each week ``TASTE_FAMILIES`` families rotate in with ``PER_FAMILY`` slots each. Every family
  with at least ``MIN_FAMILY_TRACKS`` tracks in the library has the same base chance, however big it is. Candidates
  are ranked by closeness to the user's own tracks in that family.
- **Discovery (10 slots):** families drawn with weight ``1/√(1 + tracks in library)``, so families the user has
  little or nothing of are the most likely, but any can appear. Candidates come from community charts for the
  family (no seed from the library) and are ranked by closeness to the user's overall sound.
- **Learning:** each family's chance is multiplied by how often its suggestions get liked (smoothed), and the
  taste/discovery split follows the two like rates once there is enough feedback.
"""

from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass

import numpy as np

from .taste import predict

MIX_SIZE = 30
DEFAULT_DISCOVERY = 10
DISCOVERY_RANGE = (5, 15)
TASTE_FAMILIES = 5
PER_FAMILY = 4
MIN_FAMILY_TRACKS = 3
MAX_DISCOVERY_PER_FAMILY = 3
MAX_PER_ARTIST = 2
FEEDBACK_MIN = 30
SIM_WEIGHT, LIKE_WEIGHT = 0.6, 0.4
PRIOR_RATE, PRIOR_N = 0.2, 5  # like-rate smoothing: a new family counts as 1 like in 5
MULTIPLIER_RANGE = (0.3, 3.0)


@dataclass(frozen=True)
class Candidate:
    key: str  # stable identity, e.g. "isrc:GB…"
    artist: str
    vector: np.ndarray
    family: str
    kind: str  # "taste" | "discovery"


@dataclass(frozen=True)
class Pick:
    key: str
    score: float
    family: str
    kind: str


def family_stats(feedback: dict) -> dict[str, tuple[int, int]]:
    """``{family: (shown, liked)}`` from feedback items that carry a family."""
    shown: Counter = Counter()
    liked: Counter = Counter()
    for f in feedback.get("items", []):
        if f.get("family"):
            shown[f["family"]] += 1
            liked[f["family"]] += bool(f.get("liked"))
    return {k: (shown[k], liked[k]) for k in shown}


def multiplier(shown: int, liked: int) -> float:
    """Smoothed like rate relative to the prior: 1.0 for a new family, up to 3× if loved, down to 0.3× if ignored."""
    rate = (liked + PRIOR_RATE * PRIOR_N) / (shown + PRIOR_N)
    lo, hi = MULTIPLIER_RANGE
    return min(hi, max(lo, rate / PRIOR_RATE))


def _weighted_sample(weights: dict[str, float], n: int, rng: np.random.Generator) -> list[str]:
    """Sample up to ``n`` distinct keys with probability ∝ weight (deterministic order for a given rng)."""
    keys = sorted(k for k, w in weights.items() if w > 0)
    out: list[str] = []
    while keys and len(out) < n:
        w = np.array([weights[k] for k in keys], dtype=float)
        k = keys[int(rng.choice(len(keys), p=w / w.sum()))]
        out.append(k)
        keys.remove(k)
    return out


def taste_families(
    counts: dict[str, int], stats: dict[str, tuple[int, int]], rng: np.random.Generator, n: int = TASTE_FAMILIES
) -> list[str]:
    """This week's taste families: equal base chance for every family the user has ≥ 3 tracks of."""
    weights = {f: multiplier(*stats.get(f, (0, 0))) for f, c in counts.items() if c >= MIN_FAMILY_TRACKS}
    return _weighted_sample(weights, n, rng)


def discovery_slots(
    counts: dict[str, int], all_families: list[str], stats: dict[str, tuple[int, int]], rng: np.random.Generator, slots: int
) -> dict[str, int]:
    """Spread discovery slots over families, weighted towards the ones the library has least of."""
    weights = {f: multiplier(*stats.get(f, (0, 0))) / math.sqrt(1 + counts.get(f, 0)) for f in all_families}
    out: Counter = Counter()
    for _ in range(slots):
        open_ = {f: w for f, w in weights.items() if out[f] < MAX_DISCOVERY_PER_FAMILY}
        if not open_:
            break
        out[_weighted_sample(open_, 1, rng)[0]] += 1
    return dict(out)


def split(feedback: dict) -> int:
    """Number of discovery slots: 10 by default; follows the like rates once there is enough feedback."""
    items = [f for f in feedback.get("items", []) if f.get("kind") in ("taste", "discovery")]
    if len(items) < FEEDBACK_MIN:
        return DEFAULT_DISCOVERY
    rate = {
        k: (sum(f["liked"] for f in items if f["kind"] == k) + 1) / (sum(f["kind"] == k for f in items) + 5)
        for k in ("taste", "discovery")
    }
    d = round(MIX_SIZE * rate["discovery"] / (rate["taste"] + rate["discovery"]))
    return int(min(DISCOVERY_RANGE[1], max(DISCOVERY_RANGE[0], d)))


def _unit(v: np.ndarray) -> np.ndarray:
    n = np.linalg.norm(v)
    return v / n if n > 0 else v


def score(
    cands: list[Candidate],
    family_centroids: dict[str, np.ndarray],
    taste_centroids: np.ndarray,
    feedback_w: np.ndarray | None = None,
) -> np.ndarray:
    """Taste candidates: cosine to the user's centroid for that family. Discovery: best cosine to any taste
    cluster (closeness to the overall sound). Blended 0.6/0.4 with the feedback model when it exists."""
    if not cands:
        return np.zeros(0)
    x = np.stack([_unit(c.vector) for c in cands])
    sim = np.empty(len(cands))
    for i, c in enumerate(cands):
        if c.kind == "taste" and c.family in family_centroids:
            sim[i] = float(x[i] @ _unit(family_centroids[c.family]))
        else:
            sim[i] = float((taste_centroids @ x[i]).max())
    return sim if feedback_w is None else SIM_WEIGHT * sim + LIKE_WEIGHT * predict(feedback_w, x)


def select(cands: list[Candidate], scores: np.ndarray, slots: dict[tuple[str, str], int], size: int = MIX_SIZE) -> list[Pick]:
    """Fill each (kind, family) slot count with its best candidates, ≤ 2 per artist; short families are topped up
    from the best remaining candidates of the same kind, then of any kind."""
    order = sorted(range(len(cands)), key=lambda i: (-scores[i], cands[i].key))
    per_artist: Counter = Counter()
    taken: set[int] = set()
    picks: list[Pick] = []

    def take(i: int) -> bool:
        a = cands[i].artist.casefold()
        if i in taken or per_artist[a] >= MAX_PER_ARTIST:
            return False
        taken.add(i)
        per_artist[a] += 1
        c = cands[i]
        picks.append(Pick(c.key, round(float(scores[i]), 4), c.family, c.kind))
        return True

    for (kind, fam), n in sorted(slots.items()):
        got = 0
        for i in order:
            if got >= n:
                break
            if cands[i].kind == kind and cands[i].family == fam and take(i):
                got += 1
    for kind in ("taste", "discovery"):
        want = sum(n for (k, _), n in slots.items() if k == kind)
        for i in order:
            if sum(p.kind == kind for p in picks) >= want or len(picks) >= size:
                break
            if cands[i].kind == kind:
                take(i)
    for i in order:
        if len(picks) >= size:
            break
        take(i)
    return picks[:size]
