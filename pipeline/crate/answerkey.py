"""Grade automatic tags against the user's own pre-Crate playlists (validation only, never used as tags).

Before the migration the user had sorted tracks by hand into BPM-bucket playlists ("BPM 120-130") and
genre playlists ("FI_Reggae"). They survive in backup/playlists-*.json, which makes them a free answer key.
"""

from __future__ import annotations

import re
from collections import Counter, defaultdict
from typing import Any

from .library import Effective, effective

EDGE_TOLERANCE = 0.02  # a track at 130.5 in "BPM 120-130" still counts

# Genre playlist -> the Discogs genres (and optionally specific styles) that count as a correct answer.
GENRE_PLAYLISTS: dict[str, dict[str, list[str]]] = {
    "FI_Country": {"genres": ["Folk, World, & Country"]},
    "FI_Jazz-Brazil": {"genres": ["Jazz", "Latin"]},
    "FI_Ambient": {
        "styles": [
            "Electronic---Ambient",
            "Electronic---Downtempo",
            "Electronic---Drone",
            "Electronic---Berlin-School",
            "Electronic---New Age",
            "Electronic---Dark Ambient",
            "Electronic---Experimental",
        ]
    },
    "FI_Funk": {"genres": ["Funk / Soul"]},
    "FI_Indie-Rock": {"genres": ["Rock"]},
    "FI_Hip-Hop": {"genres": ["Hip Hop"]},
    "FI_Italo-Balearic": {
        "styles": [
            "Electronic---Italo-Disco",
            "Electronic---Disco",
            "Electronic---Nu-Disco",
            "Electronic---Italodance",
            "Electronic---Synth-pop",
            "Funk / Soul---Disco",
        ]
    },
    "FI_Reggae": {"genres": ["Reggae"]},
    "FI_Latina": {"genres": ["Latin"]},
}

# Playlists named by BPM that were really sorted by feel (user, 2026-10-12: "BPM -80" = slow/beatless).
BPM_FEEL = {"BPM -80"}

_BPM_NAME = re.compile(r"^BPM\s*(?:(\d+)?\s*-\s*(\d+)|(\d+))$")


def bpm_range(name: str) -> tuple[float, float] | None:
    """``"BPM 090-100"`` -> (90, 100); ``"BPM -80"`` -> (0, 80); ``"BPM 160"`` -> (160, inf)."""
    m = _BPM_NAME.match(name.strip())
    if not m:
        return None
    if m.group(3):
        return float(m.group(3)), float("inf")
    return float(m.group(1) or 0), float(m.group(2))


def _track_id(uri: str | None) -> str | None:
    return uri.split(":")[2] if uri and uri.startswith("spotify:track:") else None


def build(backup: dict[str, Any]) -> dict[str, dict[str, Any]]:
    """track_id -> {"bpm": (lo, hi), "genre_playlists": [...]} from the backup."""
    key: dict[str, dict[str, Any]] = defaultdict(lambda: {"genre_playlists": []})
    for p in backup["playlists"]:
        name = (p.get("name") or "").strip()
        rng = bpm_range(name)
        if name in BPM_FEEL:
            rng = None
        for it in p.get("items") or []:
            tid = _track_id(it.get("uri"))
            if not tid:
                continue
            if rng:
                key[tid]["bpm"] = rng
            elif name in GENRE_PLAYLISTS:
                key[tid]["genre_playlists"].append(name)
    return dict(key)


def _in(v: float, lo: float, hi: float) -> bool:
    return lo * (1 - EDGE_TOLERANCE) <= v <= hi * (1 + EDGE_TOLERANCE)


def grade(library: dict, overrides: dict, backup: dict) -> dict[str, Any]:
    tracks = library.get("tracks") or {}
    ov = (overrides or {}).get("tracks") or {}
    key = build(backup)
    bpm = Counter()
    bpm_misses: list[tuple[str, float, tuple[float, float]]] = []
    genre = defaultdict(Counter)
    genre_found: dict[str, Counter] = defaultdict(Counter)
    for tid, k in key.items():
        t = tracks.get(tid)
        if not t or t.get("status") != "tagged":
            continue
        e: Effective = effective(t, ov.get(tid))
        if "bpm" in k and e.bpm:
            lo, hi = k["bpm"]
            if _in(e.bpm, lo, hi):
                bpm["ok"] += 1
            elif _in(e.bpm * 2, lo, hi) or _in(e.bpm / 2, lo, hi):
                bpm["octave"] += 1
                bpm_misses.append((tid, e.bpm, k["bpm"]))
            else:
                bpm["wrong"] += 1
                bpm_misses.append((tid, e.bpm, k["bpm"]))
        for pl in k["genre_playlists"]:
            rule = GENRE_PLAYLISTS[pl]
            ok = e.primary_genre in rule.get("genres", []) or e.primary_style in rule.get("styles", [])
            genre[pl]["ok" if ok else "wrong"] += 1
            genre_found[pl][e.primary_style or "?"] += 1
    return {"bpm": bpm, "bpm_misses": bpm_misses, "genre": genre, "genre_found": genre_found}


def render(result: dict[str, Any], tracks: dict) -> str:
    b = result["bpm"]
    n = sum(b.values())
    lines = ["## Answer key: your old playlists", ""]
    if n:
        lines += [
            f"BPM vs. your `BPM …` playlists ({n} tracks): **{b['ok'] / n:.0%} in the right bucket**, "
            f"{b['octave'] / n:.0%} off by an octave (×2 or ÷2), {b['wrong'] / n:.0%} otherwise wrong. Target ≥ 90 %.",
            "",
        ]
    if result["genre"]:
        lines += ["| Your playlist | Tracks | Correct | Most common detected styles |", "|---|---:|---:|---|"]
        for pl, c in sorted(result["genre"].items()):
            tot = c["ok"] + c["wrong"]
            common = ", ".join(f"{s.split('---')[-1]} {k}" for s, k in result["genre_found"][pl].most_common(4))
            lines.append(f"| {pl} | {tot} | {c['ok'] / tot:.0%} | {common} |")
        lines.append("")
    if result["bpm_misses"]:
        lines += ["BPM misses (first 40):", ""]
        for tid, v, (lo, hi) in result["bpm_misses"][:40]:
            t = tracks.get(tid, {})
            hi_s = "" if hi == float("inf") else f"{hi:g}"
            lines.append(f"- [{t.get('title')}](https://open.spotify.com/track/{tid}): detected {v}, your bucket {lo:g}–{hi_s}")
        lines.append("")
    return "\n".join(lines)
