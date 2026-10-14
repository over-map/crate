"""Deezer public API (no key): ISRC lookup, search fallback, 30 s previews.

Personal, non-redistributed use only. Previews are downloaded to a temp dir and deleted after analysis.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from .http import Http, HttpError

API = "https://api.deezer.com"
DURATION_TOLERANCE_S = 3

_BRACKETS = re.compile(r"[\(\[][^\)\]]*[\)\]]")
_DASH_SUFFIX = re.compile(r"\s+-\s+(.*(remaster|version|edit|mix|live|mono|stereo|radio).*)$", re.I)
_FEAT = re.compile(r"\b(feat\.?|ft\.?|featuring)\b.*$", re.I)
_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def normalise(text: str) -> str:
    """Lower-case, strip accents, brackets, "feat." and remaster suffixes, and punctuation."""
    s = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    s = _BRACKETS.sub(" ", s)
    s = _DASH_SUFFIX.sub("", s)
    s = _FEAT.sub("", s)
    s = s.lower().replace("&", " and ")
    return _NON_ALNUM.sub(" ", s).strip()


def is_match(title: str, artists: list[str], duration_ms: int | None, cand: dict) -> bool:
    """Accept a Deezer search hit if duration is within ±3 s and normalised title and artist agree."""
    if duration_ms is not None and abs(cand.get("duration", 0) - duration_ms / 1000) > DURATION_TOLERANCE_S:
        return False
    a, b = normalise(title), normalise(cand.get("title", ""))
    if not a or not b or not (a == b or a in b or b in a):
        return False
    cand_artist = normalise((cand.get("artist") or {}).get("name", ""))
    return any(normalise(x) and (normalise(x) == cand_artist or normalise(x) in cand_artist) for x in artists)


@dataclass(frozen=True)
class DeezerTrack:
    id: int
    preview: str | None
    bpm: float | None
    duration_s: int
    artist_id: int | None
    isrc: str | None


def _track(d: dict) -> DeezerTrack:
    bpm = d.get("bpm")
    return DeezerTrack(
        id=d["id"],
        preview=d.get("preview") or None,
        bpm=float(bpm) if bpm and bpm > 0 else None,
        duration_s=d.get("duration", 0),
        artist_id=(d.get("artist") or {}).get("id"),
        isrc=d.get("isrc"),
    )


class Deezer:
    def __init__(self, http: Http):
        self.http = http

    def _get(self, path: str, cache: bool = True, **params) -> dict | None:
        try:
            body = self.http.get(API + path, params=params or None, cache=cache)
        except HttpError as e:
            if e.status == 404:
                return None
            raise
        if not body or "error" in body:
            return None
        return body

    def track(self, deezer_id: int) -> DeezerTrack | None:
        """Fresh lookup (not cached): preview URLs are signed and expire."""
        d = self._get(f"/track/{deezer_id}", cache=False)
        return _track(d) if d else None

    def match(self, isrc: str | None, title: str, artists: list[str], duration_ms: int | None) -> DeezerTrack | None:
        """ISRC first, then search with the duration/title/artist check."""
        if isrc:
            d = self._get(f"/track/isrc:{isrc}", cache=False)
            if d and d.get("id"):
                return _track(d)
        if not artists:
            return None
        for cand in self._search(artists[0], title):
            if is_match(title, artists, duration_ms, cand):
                return self.track(cand["id"])
        return None

    def _search(self, artist: str, title: str) -> list[dict]:
        """Advanced search first; it returned nothing in Oct 2026 tests, so fall back to plain text."""
        res = self._get("/search", q=f'artist:"{artist}" track:"{title}"', limit=10) or {}
        data = res.get("data") or []
        if not data:
            data = (self._get("/search", q=f"{artist} {title}", limit=10) or {}).get("data") or []
        return data

    def related_artists(self, artist_id: int) -> list[dict]:
        return (self._get(f"/artist/{artist_id}/related", limit=20) or {}).get("data") or []

    def artist_top(self, artist_id: int, limit: int = 5) -> list[DeezerTrack]:
        data = (self._get(f"/artist/{artist_id}/top", limit=limit) or {}).get("data") or []
        return [_track(d) for d in data]

    def top_titles(self, artist_id: int, limit: int = 5) -> list[tuple[str, str]]:
        """``[(artist, title)]`` of an artist's top tracks."""
        data = (self._get(f"/artist/{artist_id}/top", limit=limit) or {}).get("data") or []
        return [((d.get("artist") or {}).get("name", ""), d.get("title", "")) for d in data]

    def search_artist(self, name: str) -> int | None:
        data = (self._get("/search/artist", q=name, limit=1) or {}).get("data") or []
        return data[0]["id"] if data else None

    def search_track(self, artist: str, title: str) -> DeezerTrack | None:
        for cand in self._search(artist, title):
            if is_match(title, [artist], None, cand):
                return self.track(cand["id"])
        return None
