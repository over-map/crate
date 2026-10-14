"""Spotify Web API (Development Mode, February 2026 rules).

Spotify is used only for identity (IDs, ISRC, title, artists, duration, cover) and for writing playlists.
No Spotify audio or metadata goes into any model (Spotify Developer Policy).

Endpoints were checked against the February 2026 migration guide:
- Liked Songs: ``GET /me/tracks`` (limit 50).
- Save, remove and contains: ``PUT|DELETE /me/library`` and ``GET /me/library/contains`` with a
  ``uris`` query parameter, at most 40 URIs per request. Unfollowing a playlist is ``DELETE /me/library``
  with its playlist URI.
- Playlists: ``/playlists/{id}/items``, and the response uses ``items[].item``.
"""

from __future__ import annotations

import base64
from collections.abc import Iterable, Iterator
from typing import Any

from .http import Http

API = "https://api.spotify.com/v1"
TOKEN_URL = "https://accounts.spotify.com/api/token"
AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
SCOPES = (
    "user-library-read user-library-modify user-follow-modify playlist-read-private "
    "playlist-read-collaborative playlist-modify-private playlist-modify-public"
)
LIBRARY_BATCH = 40
PLAYLIST_BATCH = 100
NOW_NAME = "Now"
MIX_NAME = "Crate Daily"  # the daily discovery playlist (was "Weekly Mix" until 2026-10-13)
LEGACY_MIX_NAMES = ("Weekly Mix",)
WEEKLY_NAME = MIX_NAME  # old name of the constant


def chunks(seq: list, n: int) -> Iterator[list]:
    for i in range(0, len(seq), n):
        yield seq[i : i + n]


class Spotify:
    """Spotify client authenticated with a refresh token (authorization-code flow).

    If Spotify rotates the refresh token, the new one is in ``rotated_refresh_token`` and the caller must
    store it (the CLI updates the GitHub secret).
    """

    def __init__(self, http: Http, client_id: str, client_secret: str, refresh_token: str):
        self.http = http
        self.client_id = client_id
        self.client_secret = client_secret
        self.refresh_token = refresh_token
        self.rotated_refresh_token: str | None = None
        self._access: str | None = None

    # --- auth --------------------------------------------------------------------------------------------

    def _basic(self) -> dict[str, str]:
        raw = f"{self.client_id}:{self.client_secret}".encode()
        return {"Authorization": "Basic " + base64.b64encode(raw).decode()}

    def refresh(self) -> None:
        body = self.http.request(
            "POST",
            TOKEN_URL,
            data={"grant_type": "refresh_token", "refresh_token": self.refresh_token},
            headers=self._basic(),
        )
        self._access = body["access_token"]
        new = body.get("refresh_token")
        if new and new != self.refresh_token:
            self.refresh_token = new
            self.rotated_refresh_token = new

    def exchange_code(self, code: str, redirect_uri: str) -> dict[str, Any]:
        """One-time ``crate auth`` step: authorization code -> tokens."""
        return self.http.request(
            "POST",
            TOKEN_URL,
            data={"grant_type": "authorization_code", "code": code, "redirect_uri": redirect_uri},
            headers=self._basic(),
        )

    def call(self, method: str, path: str, **kw: Any) -> Any:
        if self._access is None:
            self.refresh()
        headers = {"Authorization": f"Bearer {self._access}"}
        url = path if path.startswith("http") else API + path
        return self.http.request(method, url, headers=headers, **kw)

    def _pages(self, path: str, params: dict | None = None) -> Iterator[dict]:
        page = self.call("GET", path, params=params)
        while True:
            yield from page.get("items") or []
            nxt = page.get("next")
            if not nxt:
                return
            page = self.call("GET", nxt)

    # --- reads -------------------------------------------------------------------------------------------

    def me(self) -> dict:
        return self.call("GET", "/me")

    def saved_tracks(self) -> Iterator[dict]:
        """Liked Songs as library identity dicts (see ``library.upsert_saved``)."""
        for it in self._pages("/me/tracks", {"limit": 50}):
            t = it.get("track") or it.get("item")
            if not t or t.get("type", "track") != "track" or not t.get("id"):
                continue
            yield track_identity(t, liked_at=it.get("added_at"))

    def playlists(self) -> Iterator[dict]:
        yield from self._pages("/me/playlists", {"limit": 50})

    def playlist_items(self, playlist_id: str) -> Iterator[dict]:
        """Items of an owned or collaborative playlist: ``{"added_at", "item": track-or-episode}``."""
        for it in self._pages(f"/playlists/{playlist_id}/items", {"limit": 50}):
            yield {"added_at": it.get("added_at"), "item": it.get("item") or it.get("track")}

    def contains(self, uris: list[str]) -> dict[str, bool]:
        out: dict[str, bool] = {}
        for batch in chunks(uris, LIBRARY_BATCH):
            flags = self.call("GET", "/me/library/contains", params={"uris": ",".join(batch)})
            out.update(zip(batch, flags, strict=True))
        return out

    def search_isrc(self, isrc: str) -> dict | None:
        res = self.call("GET", "/search", params={"q": f"isrc:{isrc}", "type": "track", "limit": 1})
        items = (res.get("tracks") or {}).get("items") or []
        return items[0] if items else None

    # --- writes (callers: PlaylistWriter, migrate) --------------------------------------------------------

    def save_to_library(self, uris: Iterable[str]) -> None:
        for batch in chunks(list(uris), LIBRARY_BATCH):
            self.call("PUT", "/me/library", params={"uris": ",".join(batch)})

    def remove_from_library(self, uris: Iterable[str]) -> None:
        for batch in chunks(list(uris), LIBRARY_BATCH):
            self.call("DELETE", "/me/library", params={"uris": ",".join(batch)})

    def rename_playlist(self, playlist_id: str, name: str) -> None:
        """Rename one of Crate's own playlists (Now / Crate Daily)."""
        self.call("PUT", f"/playlists/{playlist_id}", json_body={"name": name})

    def create_playlist(self, name: str, description: str = "") -> str:
        res = self.call("POST", "/me/playlists", json_body={"name": name, "public": False, "description": description})
        return res["id"]


def track_identity(t: dict, liked_at: str | None = None) -> dict[str, Any]:
    images = (t.get("album") or {}).get("images") or []
    return {
        "id": t["id"],
        "uri": t.get("uri") or f"spotify:track:{t['id']}",
        "isrc": (t.get("external_ids") or {}).get("isrc"),
        "title": t.get("name"),
        "artists": [a["name"] for a in t.get("artists") or []],
        "duration_ms": t.get("duration_ms"),
        "cover": images[0]["url"] if images else None,
        "liked_at": liked_at,
    }


class PlaylistWriter:
    """The only code allowed to replace playlist contents, and only for allow-listed playlist IDs."""

    def __init__(self, spotify: Spotify, allowed_ids: Iterable[str]):
        self.spotify = spotify
        self.allowed = set(allowed_ids)

    def replace(self, playlist_id: str, uris: list[str]) -> None:
        if playlist_id not in self.allowed:
            raise PermissionError(f"playlist {playlist_id} is not Now or Crate Daily")
        batches = list(chunks(uris, PLAYLIST_BATCH)) or [[]]
        self.spotify.call("PUT", f"/playlists/{playlist_id}/items", json_body={"uris": batches[0]})
        for batch in batches[1:]:
            self.spotify.call("POST", f"/playlists/{playlist_id}/items", json_body={"uris": batch})


def find_or_create(spotify: Spotify, name: str, known_id: str | None, my_id: str) -> str:
    """Playlist ID for Now / Crate Daily: the known ID, else an owned playlist with that name, else a new one."""
    if known_id:
        return known_id
    for p in spotify.playlists():
        if p.get("name") == name and (p.get("owner") or {}).get("id") == my_id:
            return p["id"]
    return spotify.create_playlist(name, "Managed by Crate. Contents are replaced automatically.")
