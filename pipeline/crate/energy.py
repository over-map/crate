"""Energy 1–10 as a decile of a raw score, relative to this library (build spec §6.6)."""

from __future__ import annotations

import numpy as np

WEIGHTS = {"arousal": 0.45, "loudness": 0.25, "onset_rate": 0.2, "danceability": 0.1}


def raw_scores(features: dict[str, dict[str, float]]) -> dict[str, float]:
    """Weighted sum of z-scores across the library. ``features[track_id]`` has the four keys in WEIGHTS."""
    ids = sorted(features)
    if not ids:
        return {}
    total = np.zeros(len(ids))
    for name, w in WEIGHTS.items():
        col = np.array([features[i][name] for i in ids], dtype=float)
        sd = col.std()
        total += w * ((col - col.mean()) / sd if sd > 0 else np.zeros_like(col))
    return {i: round(float(v), 4) for i, v in zip(ids, total, strict=True)}


def deciles(raw: dict[str, float]) -> dict[str, int]:
    """Map raw scores to 1–10 by rank. Ties share the lower decile; order is stable by track ID."""
    ids = sorted(raw, key=lambda i: (raw[i], i))
    n = len(ids)
    out: dict[str, int] = {}
    for rank, i in enumerate(ids):
        out[i] = min(10, rank * 10 // n + 1)
    # equal raw values get the same energy
    first: dict[float, int] = {}
    for i in ids:
        first.setdefault(raw[i], out[i])
        out[i] = first[raw[i]]
    return out


def recompute(library: dict) -> int:
    """Recompute ``auto.energy_raw`` and ``auto.energy`` for every track with ``auto.energy_features``.

    Energy is relative to the whole library, so this runs after every analysis batch. Returns how many
    tracks' energy changed.
    """
    tracks = library.get("tracks", {})
    feats = {
        tid: t["auto"]["energy_features"]
        for tid, t in tracks.items()
        if (t.get("auto") or {}).get("energy_features") and all(k in t["auto"]["energy_features"] for k in WEIGHTS)
    }
    raw = raw_scores(feats)
    changed = 0
    for tid, e in deciles(raw).items():
        auto = tracks[tid]["auto"]
        auto["energy_raw"] = raw[tid]
        if auto.get("energy") != e:
            auto["energy"] = e
            changed += 1
    return changed
