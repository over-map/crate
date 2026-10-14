"""Weekly API contract smoke test (build spec §14.3).

Calls each upstream endpoint Crate depends on once and checks that the fields we read are still there,
so upstream changes are noticed before data goes stale ("Spotify API changed: field X missing").
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

# endpoint name -> list of dotted field paths ("[]" means "first element of a list")
CONTRACTS: dict[str, list[str]] = {
    "spotify GET /me": ["id"],
    "spotify GET /me/tracks": [
        "items",
        "items[].added_at",
        "items[].track.id",
        "items[].track.uri",
        "items[].track.external_ids.isrc",
        "items[].track.duration_ms",
        "items[].track.artists[].name",
    ],
    "spotify GET /me/playlists": ["items", "items[].id", "items[].name", "items[].owner.id"],
    "spotify GET /search isrc": ["tracks.items"],
    "deezer GET /track/isrc": ["id", "preview", "bpm", "duration", "isrc", "artist.id"],
    "deezer GET /search": ["data", "data[].id", "data[].title", "data[].duration", "data[].artist.name"],
    "deezer GET /artist/related": ["data", "data[].id", "data[].name"],
    "lastfm track.getsimilar": ["similartracks.track", "similartracks.track[].name", "similartracks.track[].artist.name"],
}


def missing_fields(body: Any, paths: list[str]) -> list[str]:
    """Paths not present in ``body``. An empty list where an element is expected counts as present."""
    out = []
    for path in paths:
        cur: Any = body
        ok = True
        for part in path.split("."):
            is_list = part.endswith("[]")
            name = part[:-2] if is_list else part
            if not isinstance(cur, dict) or name not in cur:
                ok = False
                break
            cur = cur[name]
            if is_list:
                if not isinstance(cur, list):
                    ok = False
                    break
                if not cur:
                    break
                cur = cur[0]
        if not ok:
            out.append(path)
    return out


def run(probes: dict[str, Callable[[], Any]]) -> list[str]:
    """Run each probe and return human-readable problems (empty list = all good)."""
    problems = []
    for name, probe in probes.items():
        try:
            body = probe()
        except Exception as e:
            problems.append(f"{name} failed: {str(e)[:120]}")
            continue
        for path in missing_fields(body, CONTRACTS[name]):
            problems.append(f"{name.split()[0].capitalize()} API changed: {name} field {path} missing")
    return problems
