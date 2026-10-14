"""Discogs (free personal token): release genres and styles as a second opinion on the audio style.

Discogs styles use the same vocabulary the audio model was trained on, so agreement is easy to measure.
Results go to ``data/discogs.json`` (Actions/CLI only): ``{"tracks": {id: {...}}}``. Misses are stored too,
so they aren't searched again for ``REFRESH_DAYS``.
"""

from __future__ import annotations

from .deezer import normalise
from .http import Http

API = "https://api.discogs.com/database/search"
USER_AGENT = "Crate/0.1 +personal-music-tagger"
REFRESH_DAYS = 90


class Discogs:
    def __init__(self, http: Http, token: str):
        self.http = http
        self.headers = {"Authorization": f"Discogs token={token}", "User-Agent": USER_AGENT}

    def lookup(self, artist: str, title: str) -> dict | None:
        """Best release containing this track: ``{release_id, title, year, genres, styles}``, or None."""
        clean = title.split(" - ")[0]
        res = (
            self.http.get(API, params={"artist": artist, "track": clean, "type": "release", "per_page": 5}, headers=self.headers)
            or {}
        )
        results = res.get("results") or []
        if not results:
            return None
        # prefer a release whose artist part of "Artist - Title" matches
        want = normalise(artist)
        best = next((r for r in results if want and want in normalise(r.get("title", "").split(" - ")[0])), results[0])
        return {
            "release_id": best.get("id"),
            "title": best.get("title"),
            "year": int(best["year"]) if str(best.get("year", "")).isdigit() else None,
            "genres": best.get("genre") or [],
            "styles": best.get("style") or [],
        }
