"""Library data (``data/library.json``, ``data/overrides.json``) and effective-tag merging.

Effective tags are the automatic tags with the user's overrides applied field by field.
The rules are in docs/query-semantics.md and are shared with the Swift app.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

SCHEMA = 1
MOOD_THRESHOLD = 0.5
VOCAL_THRESHOLD = 0.5


class UnsupportedSchema(RuntimeError):
    """A data file was written by a newer version of Crate."""


def genre_of(style: str) -> str:
    """Parent genre of a canonical style label: ``"Electronic---Techno"`` -> ``"Electronic"``."""
    return style.split("---", 1)[0]


@dataclass(frozen=True)
class Effective:
    """A track's effective tags. ``None`` means unknown (the field was never tagged)."""

    primary_style: str | None
    primary_genre: str | None
    bpm: float | None
    key: str | None
    energy: int | None
    moods: frozenset[str] | None
    vocal: bool | None
    family: str | None = None


def _ranked_styles(styles: dict[str, float]) -> list[str]:
    return sorted(styles, key=lambda s: (-styles[s], s))


def effective(track: dict[str, Any], override: dict[str, Any] | None = None) -> Effective:
    """Merge a library track's ``auto`` tags with its override (see docs/query-semantics.md)."""
    auto = track.get("auto") or {}
    ov = override or {}

    styles_add = list(ov.get("styles_add") or [])
    styles_remove = set(ov.get("styles_remove") or [])
    genres_add = list(ov.get("genres_add") or [])
    genres_remove = set(ov.get("genres_remove") or [])

    if styles_add:
        primary_style: str | None = styles_add[0]
    else:
        candidates = [
            s for s in _ranked_styles(auto.get("styles") or {}) if s not in styles_remove and genre_of(s) not in genres_remove
        ]
        primary_style = candidates[0] if candidates else None

    if not styles_add and genres_add:
        primary_genre: str | None = genres_add[0]
        if primary_style is not None and genre_of(primary_style) != primary_genre:
            primary_style = None
    else:
        primary_genre = genre_of(primary_style) if primary_style else None

    auto_moods = auto.get("moods")
    moods_add = ov.get("moods_add") or []
    if auto_moods is None and not moods_add:
        moods: frozenset[str] | None = None
    else:
        present = {m for m, c in (auto_moods or {}).items() if c >= MOOD_THRESHOLD}
        moods = frozenset((present | set(moods_add)) - set(ov.get("moods_remove") or []))

    if ov.get("vocal") is not None:
        vocal: bool | None = bool(ov["vocal"])
    elif auto.get("vocal") is not None:
        vocal = auto["vocal"] >= VOCAL_THRESHOLD
    else:
        vocal = None

    def pick(name: str) -> Any:
        return ov[name] if ov.get(name) is not None else auto.get(name)

    return Effective(
        family=ov.get("family") or track.get("family"),
        primary_style=primary_style,
        primary_genre=primary_genre,
        bpm=pick("bpm"),
        key=pick("key"),
        energy=pick("energy"),
        moods=moods,
        vocal=vocal,
    )


# --- files -----------------------------------------------------------------------------------------------


def now_iso() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path: Path, default: dict[str, Any]) -> dict[str, Any]:
    """Read a data file, or return ``default`` if it doesn't exist. Refuses newer schemas."""
    if not path.exists():
        return default
    data = json.loads(path.read_text())
    if isinstance(data, dict) and data.get("schema", SCHEMA) > SCHEMA:
        raise UnsupportedSchema(f"{path.name} has schema {data['schema']}; update Crate")
    return data


def save_json(path: Path, data: Any) -> bool:
    """Write JSON deterministically. Returns True if the file content changed."""
    text = json.dumps(data, indent=1, ensure_ascii=False, sort_keys=True) + "\n"
    if path.exists() and path.read_text() == text:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text)
    tmp.replace(path)
    return True


def empty_library() -> dict[str, Any]:
    return {"schema": SCHEMA, "generated_at": now_iso(), "tracks": {}}


def empty_overrides() -> dict[str, Any]:
    return {"schema": SCHEMA, "tracks": {}}


def upsert_saved(library: dict[str, Any], saved: list[dict[str, Any]]) -> dict[str, int]:
    """Bring ``library`` in line with the current Liked Songs.

    ``saved`` items are ``{"id", "uri", "isrc", "title", "artists", "duration_ms", "cover", "liked_at"}``.
    New tracks become ``pending``; identity fields of known tracks are refreshed; unliked tracks are removed
    (their overrides live in overrides.json and are kept). Returns counts.
    """
    tracks = library.setdefault("tracks", {})
    seen = set()
    added = 0
    for item in saved:
        tid = item["id"]
        seen.add(tid)
        identity = {k: item.get(k) for k in ("uri", "isrc", "title", "artists", "duration_ms", "cover", "liked_at")}
        if tid in tracks:
            tracks[tid].update(identity)
        else:
            tracks[tid] = {**identity, "status": "pending"}
            added += 1
    removed = [tid for tid in tracks if tid not in seen]
    for tid in removed:
        del tracks[tid]
    return {"added": added, "removed": len(removed), "total": len(tracks)}
