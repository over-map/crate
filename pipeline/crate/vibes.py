"""Vibes: user-named moods defined by example ("sounds like these tracks/artists").

A vibe is ``{"name", "seeds": [track ids], "artists": [names], "exclude": [track ids], "share": 0.2}`` in
``data/vibes.json`` (written by the app; seeded once by ``crate vibes --seed``). Its members are the tracks
whose sound-similarity vector is closest to the seeds. Rules (shared with Swift, see
docs/query-semantics.md#vibes):

1. Explicit seeds = ``seeds`` ∪ tracks whose artist equals (case-insensitive) one in ``artists``. Tag seeds = tracks
   with a normalised tag in ``seed_tags``. Only tracks with a vector count.
2. Tag seeds must also sound like the vibe: rank once with explicit ∪ tag seeds (rule 3); tag seeds outside that
   pass's top k are dropped. No seeds left → the vibe has no members.
3. Score of a track = cos(track, P) − 0.2 · cos(track, N), where P = normalise(mean of normalised seed vectors) and
   N the same over the excluded tracks that have vectors (no N term when there are none). Rank by score descending,
   ties by track ID ascending; k = ceil(share × number of tracks with a vector).
4. Members = top k ∪ seeds − ``exclude``. Excluded tracks are not replaced one-for-one; their negative pull is rule 3.
5. Energy must fit too (the 32-d sound vector barely encodes energy, so "Energetic" used to pull in quiet tracks).
   Take the effective energies of the seeds; with at least 3, drop a seed more than 4 away from the median of the
   others, and keep only top-k members within 1 of the remaining seeds' min–max. Seeds and tracks with unknown
   energy always stay.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Mapping

import numpy as np

from . import tags

DEFAULT_SHARE = 0.2
TrackTags = Mapping[str, "frozenset[str] | None"]


def _unit(v: Iterable[float]) -> np.ndarray:
    a = np.asarray(list(v), dtype=np.float64)
    n = np.linalg.norm(a)
    return a / n if n > 0 else a


NEGATIVE_WEIGHT = 0.2  # 0.5 overcorrected on the real library (2026-10-12)
ENERGY_MARGIN = 1
ENERGY_OUTLIER = 4
ENERGY_MIN_SEEDS = 3
Energies = Mapping[str, "int | None"]


def explicit_seeds(vibe: Mapping, artists: Mapping[str, list[str]], vectors: Mapping) -> set[str]:
    names = {a.casefold() for a in vibe.get("artists") or []}
    seeds = set(vibe.get("seeds") or [])
    if names:
        seeds |= {tid for tid, arts in artists.items() if any(a.casefold() in names for a in arts or [])}
    return {s for s in seeds if s in vectors}


def tag_seeds(vibe: Mapping, vectors: Mapping, track_tags: TrackTags | None) -> set[str]:
    wanted = {n for n in (tags.normalise(x) for x in vibe.get("seed_tags") or []) if n}
    if not wanted or not track_tags:
        return set()
    return {tid for tid, tt in track_tags.items() if tt and wanted & tt and tid in vectors}


def _centroid(ids: Iterable[str], vectors: Mapping) -> np.ndarray | None:
    ids = sorted(ids)
    return _unit(np.mean([_unit(vectors[i]) for i in ids], axis=0)) if ids else None


def _rank(pos: set[str], neg: set[str], vectors: Mapping) -> list[tuple[str, float]]:
    c = _centroid(pos, vectors)
    if c is None:
        return []
    n = _centroid(neg, vectors)
    scored = []
    for tid, v in vectors.items():
        x = _unit(v)
        score = float(x @ c) - (NEGATIVE_WEIGHT * float(x @ n) if n is not None else 0.0)
        scored.append((tid, score))
    return sorted(scored, key=lambda x: (-x[1], x[0]))


def _k(vibe: Mapping, vectors: Mapping) -> int:
    return math.ceil(float(vibe.get("share", DEFAULT_SHARE)) * len(vectors) - 1e-9)


def seed_tracks(
    vibe: Mapping, artists: Mapping[str, list[str]], vectors: Mapping, track_tags: TrackTags | None = None
) -> list[str]:
    """Final seeds: explicit seeds plus the tag seeds that survive the first ranking pass (rule 2)."""
    explicit = explicit_seeds(vibe, artists, vectors)
    from_tags = tag_seeds(vibe, vectors, track_tags) - explicit
    if not from_tags:
        return sorted(explicit)
    neg = {e for e in vibe.get("exclude") or [] if e in vectors}
    first = {tid for tid, _ in _rank(explicit | from_tags, neg, vectors)[: _k(vibe, vectors)]}
    return sorted(explicit | (from_tags & first))


def ranked(
    vibe: Mapping, artists: Mapping[str, list[str]], vectors: Mapping, track_tags: TrackTags | None = None
) -> list[tuple[str, float]]:
    """Every track with a vector and its score (cosine to seeds − 0.2 × cosine to excluded), best first."""
    seeds = set(seed_tracks(vibe, artists, vectors, track_tags))
    neg = {e for e in vibe.get("exclude") or [] if e in vectors}
    return _rank(seeds, neg, vectors)


def energies(tracks: Mapping[str, Mapping], overrides: Mapping[str, Mapping] | None = None) -> dict[str, int | None]:
    """Effective energy per track (an override wins), the ``energy`` argument of ``members``."""
    from .library import effective

    ov = overrides or {}
    return {tid: effective(dict(t), ov.get(tid)).energy for tid, t in tracks.items()}


def energy_band(seeds: Iterable[str], energy: Energies | None) -> tuple[int, int] | None:
    """Rule 5: the energies a member may have, or None (no gate) with fewer than 3 seeds of known energy."""
    if not energy:
        return None
    known = [e for e in (energy.get(s) for s in seeds) if e is not None]
    if len(known) < ENERGY_MIN_SEEDS:
        return None
    kept = [e for i, e in enumerate(known) if abs(e - float(np.median(known[:i] + known[i + 1 :]))) <= ENERGY_OUTLIER]
    if not kept:
        return None
    return min(kept) - ENERGY_MARGIN, max(kept) + ENERGY_MARGIN


def members(
    vibe: Mapping,
    artists: Mapping[str, list[str]],
    vectors: Mapping,
    track_tags: TrackTags | None = None,
    energy: Energies | None = None,
) -> set[str]:
    order = ranked(vibe, artists, vectors, track_tags)
    if not order:
        return set()
    seeds = set(seed_tracks(vibe, artists, vectors, track_tags))
    top = [tid for tid, _ in order[: _k(vibe, vectors)]]
    band = energy_band(seeds, energy)
    if band is not None:
        lo, hi = band
        top = [t for t in top if (e := (energy or {}).get(t)) is None or lo <= e <= hi]
    return (set(top) | seeds) - set(vibe.get("exclude") or [])


def all_members(
    vibes: Mapping[str, Mapping],
    artists: Mapping[str, list[str]],
    vectors: Mapping,
    track_tags: TrackTags | None = None,
    energy: Energies | None = None,
) -> dict[str, set[str]]:
    return {vid: members(v, artists, vectors, track_tags, energy) for vid, v in vibes.items()}
