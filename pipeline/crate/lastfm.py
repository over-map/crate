"""Last.fm (free API key): similar tracks and artists for Weekly Mix candidates."""

from __future__ import annotations

from .http import Http

API = "https://ws.audioscrobbler.com/2.0/"


class LastFm:
    def __init__(self, http: Http, api_key: str):
        self.http = http
        self.api_key = api_key

    def _call(self, method: str, **params) -> dict:
        body = self.http.get(API, params={"method": method, "api_key": self.api_key, "format": "json", **params}, cache=True)
        if not body or "error" in body:
            return {}
        return body

    def similar_tracks(self, artist: str, title: str, limit: int = 50) -> list[tuple[str, str]]:
        """``[(artist, title)]`` similar to a track."""
        res = self._call("track.getsimilar", artist=artist, track=title, limit=limit, autocorrect=1)
        return [(t["artist"]["name"], t["name"]) for t in (res.get("similartracks") or {}).get("track") or []]

    def similar_artists(self, artist: str, limit: int = 20) -> list[str]:
        res = self._call("artist.getsimilar", artist=artist, limit=limit, autocorrect=1)
        return [a["name"] for a in (res.get("similarartists") or {}).get("artist") or []]

    def artist_tags(self, artist: str) -> list[dict]:
        """Raw ``[{"name", "count"}]`` top tags of an artist (count 0–100)."""
        res = self._call("artist.gettoptags", artist=artist, autocorrect=1)
        return [{"name": t["name"], "count": int(t.get("count", 0))} for t in (res.get("toptags") or {}).get("tag") or []]

    def tag_top_tracks(self, tag: str, limit: int = 50, page: int = 1) -> list[tuple[str, str]]:
        """``[(artist, title)]`` most played under a community tag (used for discovery)."""
        res = self._call("tag.gettoptracks", tag=tag, limit=limit, page=page)
        return [(t["artist"]["name"], t["name"]) for t in (res.get("tracks") or {}).get("track") or []]

    def top_tracks(self, artist: str, limit: int = 5) -> list[tuple[str, str]]:
        res = self._call("artist.gettoptracks", artist=artist, limit=limit, autocorrect=1)
        return [(artist, t["name"]) for t in (res.get("toptracks") or {}).get("track") or []]
