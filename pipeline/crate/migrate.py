"""One-time, destructive migration (build spec §9): like every track in owned playlists, then remove playlists.

``plan()`` is a pure function over a backup, so the dry run and the real run see the same thing. The
real run needs the 6-character code the dry run prints, which is derived from the plan's hash: if anything
changes between the two (a new playlist, a track liked meanwhile), the code changes and execute refuses.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .spotify import LEGACY_MIX_NAMES, MIX_NAME, NOW_NAME, Spotify

RECOVER_NOTE = (
    "Deleted playlists can be restored at spotify.com/account → Recover playlists "
    "(Spotify keeps them for 90 days; check the page for the current window)."
)


@dataclass
class Plan:
    to_like: list[str]
    already_liked: list[str]
    skipped: dict[str, list[str]]  # reason -> URIs or names
    delete_owned: list[dict[str, str]]  # {"id", "name"}
    unfollow: list[dict[str, str]]
    code: str = ""

    def summary(self) -> str:
        lines = [
            f"Tracks to like:        {len(self.to_like)}",
            f"Already liked:         {len(self.already_liked)}",
        ]
        for reason, items in sorted(self.skipped.items()):
            lines.append(f"Skipped ({reason}): {len(items)}")
        lines += [
            f"Owned playlists to delete:      {len(self.delete_owned)}",
            f"Followed playlists to unfollow: {len(self.unfollow)}",
            "",
            *[f"  delete   {p['name']}" for p in self.delete_owned],
            *[f"  unfollow {p['name']}" for p in self.unfollow],
            "",
            f"Confirmation code: {self.code}",
        ]
        return "\n".join(lines)


def backup(spotify: Spotify, my_id: str) -> dict[str, Any]:
    """Every playlist; full items for owned/collaborative ones, metadata only for followed ones."""
    out: dict[str, Any] = {"schema": 1, "taken_at": datetime.now(UTC).isoformat(), "user": my_id, "playlists": []}
    for p in spotify.playlists():
        owned = (p.get("owner") or {}).get("id") == my_id or bool(p.get("collaborative"))
        entry: dict[str, Any] = {
            "id": p["id"],
            "name": p.get("name"),
            "owner": (p.get("owner") or {}).get("id"),
            "collaborative": bool(p.get("collaborative")),
            "owned": owned,
        }
        if owned:
            items = []
            for it in spotify.playlist_items(p["id"]):
                t = it.get("item") or {}
                items.append(
                    {
                        "uri": t.get("uri"),
                        "name": t.get("name"),
                        "type": t.get("type"),
                        "artists": [a.get("name") for a in t.get("artists") or []],
                        "isrc": (t.get("external_ids") or {}).get("isrc"),
                        "added_at": it.get("added_at"),
                        "is_local": bool(t.get("is_local")),
                        "is_playable": t.get("is_playable"),
                    }
                )
            entry["items"] = items
        out["playlists"].append(entry)
    return out


def plan(backup_data: dict[str, Any], liked: set[str], crate_playlist_ids: set[str]) -> Plan:
    """Pure planner. ``liked`` is the set of track URIs already in Liked Songs."""
    to_like: list[str] = []
    seen: set[str] = set()
    already: set[str] = set()
    skipped: dict[str, list[str]] = {}
    delete_owned, unfollow = [], []

    for p in backup_data["playlists"]:
        if p["id"] in crate_playlist_ids or (p["owned"] and p.get("name") in (NOW_NAME, MIX_NAME, *LEGACY_MIX_NAMES)):
            skipped.setdefault("crate playlist", []).append(p.get("name") or p["id"])
            continue
        if not p["owned"]:
            unfollow.append({"id": p["id"], "name": p.get("name") or ""})
            continue
        delete_owned.append({"id": p["id"], "name": p.get("name") or ""})
        for it in p.get("items") or []:
            uri = it.get("uri") or ""
            if it.get("is_local") or uri.startswith("spotify:local:"):
                skipped.setdefault("local file", []).append(uri)
            elif it.get("type") == "episode" or uri.startswith("spotify:episode:"):
                skipped.setdefault("episode", []).append(uri)
            elif not uri.startswith("spotify:track:") or it.get("is_playable") is False:
                skipped.setdefault("unavailable", []).append(uri or it.get("name") or "?")
            elif uri in liked:
                already.add(uri)
            elif uri not in seen:
                seen.add(uri)
                to_like.append(uri)

    result = Plan(
        to_like=to_like,
        already_liked=sorted(already),
        skipped={k: sorted(set(v)) for k, v in skipped.items()},
        delete_owned=delete_owned,
        unfollow=unfollow,
    )
    result.code = confirmation_code(result)
    return result


def confirmation_code(p: Plan) -> str:
    payload = json.dumps({k: v for k, v in asdict(p).items() if k != "code"}, sort_keys=True).encode()
    return hashlib.sha256(payload).hexdigest()[:6].upper()


@dataclass
class Checkpoint:
    """Progress file so an interrupted run resumes instead of starting over."""

    path: Path
    liked: list[str] = field(default_factory=list)
    removed: list[str] = field(default_factory=list)

    @classmethod
    def load(cls, path: Path) -> Checkpoint:
        if path.exists():
            d = json.loads(path.read_text())
            return cls(path, d.get("liked", []), d.get("removed", []))
        return cls(path)

    def save(self) -> None:
        self.path.write_text(json.dumps({"liked": self.liked, "removed": self.removed}))


class MissingAfterLike(RuntimeError):
    pass


def execute(spotify: Spotify, p: Plan, code: str, checkpoint: Checkpoint, batch: int = 40) -> None:
    """Like, verify, then delete/unfollow. Refuses unless ``code`` matches the plan."""
    if code.strip().upper() != p.code:
        raise PermissionError(f"confirmation code {code!r} does not match this plan ({p.code}); re-run the dry run")
    done = set(checkpoint.liked)
    todo = [u for u in p.to_like if u not in done]
    for i in range(0, len(todo), batch):
        chunk = todo[i : i + batch]
        spotify.save_to_library(chunk)
        checkpoint.liked.extend(chunk)
        checkpoint.save()

    flags = spotify.contains(p.to_like)
    missing = [u for u, ok in flags.items() if not ok]
    if missing:
        raise MissingAfterLike("not in Liked Songs after liking; nothing deleted:\n" + "\n".join(missing))

    gone = set(checkpoint.removed)
    for pl in p.delete_owned + p.unfollow:
        if pl["id"] in gone:
            continue
        spotify.remove_from_library([f"spotify:playlist:{pl['id']}"])
        checkpoint.removed.append(pl["id"])
        checkpoint.save()
