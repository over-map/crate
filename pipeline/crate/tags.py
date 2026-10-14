"""Community and hand-entered tags (Last.fm artist tags, Discogs styles, Beatport genres), normalised.

Normalisation and synonyms are shared with Swift through spec/tag_synonyms.json.
A track's tags are stored ready-made in ``library.json`` as ``tracks[id].tags`` (Actions write them).
"""

from __future__ import annotations

import json
import re
import unicodedata
from functools import lru_cache
from pathlib import Path

SYNONYMS_FILE = Path(__file__).resolve().parents[2] / "spec" / "tag_synonyms.json"
_SEP = re.compile(r"[-_/.,]")
_WS = re.compile(r"\s+")


@lru_cache(maxsize=1)
def synonyms() -> dict[str, str]:
    return json.loads(SYNONYMS_FILE.read_text())["synonyms"]


def normalise(tag: str) -> str:
    """``"  Hip-Hop "`` -> ``"hip hop"``; ``"Italy"`` -> ``"italian"`` (see spec/tag_synonyms.json)."""
    s = unicodedata.normalize("NFKD", tag)
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).lower()
    s = s.replace("'", "").replace("’", "").replace("&", " and ")
    s = _WS.sub(" ", _SEP.sub(" ", s)).strip()
    return synonyms().get(s, s)


def track_tags(track: dict) -> frozenset[str] | None:
    """Normalised tags of a library track, or None if it has never been tagged."""
    raw = track.get("tags")
    if raw is None:
        return None
    return frozenset(t for t in (normalise(x) for x in raw) if t)


# --- sources -------------------------------------------------------------------------------------------

LASTFM_MIN_COUNT = 10  # Last.fm tag weight 0–100 relative to the artist's top tag
LASTFM_TOP = 15
REFRESH_DAYS = 30


def lastfm_artist_tags(raw: list[dict]) -> list[str]:
    """Keep an artist's meaningful tags: weight ≥ 10, top 15."""
    return [t["name"] for t in raw if int(t.get("count", 0)) >= LASTFM_MIN_COUNT][:LASTFM_TOP]


def merge(library: dict, artist_tags: dict, beatport: dict, discogs: dict | None = None) -> int:
    """Write ``tracks[id].tags`` = normalised union of every source. Returns how many tracks changed.

    - ``artist_tags``: data/artist_tags.json ``{"artists": {name: {"lastfm": [...], "fetched_at": ...}}}``
    - ``beatport``: data/beatport.json (written by hand) ``{"tracks": {id: [genre, sub-genre]}}`` (per track only)
    - ``discogs``: data/discogs.json ``{"tracks": {id: {"styles": [...], "genres": [...]}}}``
    A track with no source at all gets no ``tags`` key (unknown), so tag filters skip it. ``tag_sources`` keeps the
    normalised tags per source (``lastfm`` / ``discogs`` / ``beatport``) so the app can credit each tag.
    """
    arts = artist_tags.get("artists", {})
    bp_t = beatport.get("tracks", {})
    dc = (discogs or {}).get("tracks", {})
    changed = 0
    for tid, t in library.get("tracks", {}).items():
        by: dict[str, list[str]] = {"lastfm": [], "discogs": [], "beatport": []}
        known = False
        for a in t.get("artists") or []:
            if a in arts:
                known = True
                by["lastfm"] += arts[a].get("lastfm") or []
        if tid in bp_t:
            known = True
            by["beatport"] += bp_t[tid]
        if tid in dc:
            known = True
            by["discogs"] += (dc[tid].get("styles") or []) + (dc[tid].get("genres") or [])
        norm = {k: sorted({n for n in (normalise(x) for x in v) if n}) for k, v in by.items()}
        new = sorted({n for v in norm.values() for n in v}) if known else None
        sources = {k: v for k, v in norm.items() if v} if known else None
        if new is None:
            if "tags" in t or "tag_sources" in t:
                t.pop("tags", None)
                t.pop("tag_sources", None)
                changed += 1
        elif t.get("tags") != new or t.get("tag_sources") != sources:
            t["tags"] = new
            t["tag_sources"] = sources
            changed += 1
    return changed
