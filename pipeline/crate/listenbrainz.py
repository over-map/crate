"""ListenBrainz LB Radio: an optional extra source of Weekly Mix candidates.

Used only when ``LISTENBRAINZ_TOKEN`` is set. Any failure is ignored: Weekly Mix is built from the other
sources (build spec §14.6).
"""

from __future__ import annotations

import logging

from .http import Http

API = "https://api.listenbrainz.org/1/explore/lb-radio"
log = logging.getLogger(__name__)


class ListenBrainz:
    def __init__(self, http: Http, token: str | None):
        self.http = http
        self.token = token

    def radio(self, artist: str) -> list[tuple[str, str]]:
        """``[(artist, title)]`` from an artist-seeded LB Radio playlist (JSPF)."""
        if not self.token:
            return []
        try:
            body = self.http.get(
                API,
                params={"prompt": f"artist:({artist})", "mode": "easy"},
                headers={"Authorization": f"Token {self.token}"},
                cache=True,
            )
        except Exception as e:
            log.info("ListenBrainz unavailable: %s", e)
            return []
        tracks = (((body or {}).get("payload") or {}).get("jspf") or {}).get("playlist", {}).get("track") or []
        return [(t.get("creator", ""), t.get("title", "")) for t in tracks if t.get("creator") and t.get("title")]
