"""Tag queries: which tracks match, and the shuffled sample that goes to Now.

Semantics are in docs/query-semantics.md and tested against spec/query_vectors.json (shared with Swift).
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Any

from . import keys, tags
from .library import Effective, effective

DEFAULT_LIMIT = 100


@dataclass(frozen=True)
class Range:
    min: float
    max: float

    def contains(self, v: float) -> bool:
        return self.min <= v <= self.max


@dataclass(frozen=True)
class Query:
    families_include: frozenset[str] = frozenset()
    family_styles: tuple[tuple[str, frozenset[str]], ...] = ()
    genres_include: frozenset[str] = frozenset()
    genres_exclude: frozenset[str] = frozenset()
    styles_include: frozenset[str] = frozenset()
    styles_exclude: frozenset[str] = frozenset()
    bpm: Range | None = None
    half_double: bool = False
    energy: Range | None = None
    moods_include: frozenset[str] = frozenset()
    moods_exclude: frozenset[str] = frozenset()
    key: str | None = None
    key_compatible: bool = False
    vocal: str = "any"
    vibes_include: frozenset[str] = frozenset()
    tags_include: frozenset[str] = frozenset()
    limit: int = DEFAULT_LIMIT
    _keyset: frozenset[str] = field(default=frozenset(), repr=False, compare=False)

    @classmethod
    def from_json(cls, d: dict[str, Any]) -> Query:
        """Parse the JSON form used in presets.json and the vectors. Missing fields are unconstrained."""
        bpm = d.get("bpm")
        energy = d.get("energy")
        key = d.get("key")
        vocal = d.get("vocal") or "any"
        if vocal not in ("any", "vocal", "instrumental"):
            raise ValueError(f"bad vocal {vocal!r}")
        code = key.get("camelot") if key else None
        if code is not None and not keys.is_camelot(code):
            raise ValueError(f"bad key {code!r}")
        compat = bool(key.get("compatible")) if key else False
        return cls(
            families_include=frozenset(d.get("families_include") or ()),
            family_styles=tuple(sorted((f, frozenset(st)) for f, st in (d.get("family_styles") or {}).items())),
            genres_include=frozenset(d.get("genres_include") or ()),
            genres_exclude=frozenset(d.get("genres_exclude") or ()),
            styles_include=frozenset(d.get("styles_include") or ()),
            styles_exclude=frozenset(d.get("styles_exclude") or ()),
            bpm=Range(bpm["min"], bpm["max"]) if bpm else None,
            half_double=bool(bpm.get("half_double")) if bpm else False,
            energy=Range(energy["min"], energy["max"]) if energy else None,
            moods_include=frozenset(d.get("moods_include") or ()),
            moods_exclude=frozenset(d.get("moods_exclude") or ()),
            key=code,
            key_compatible=compat,
            vocal=vocal,
            vibes_include=frozenset(d.get("vibes_include") or ()),
            tags_include=frozenset(n for n in (tags.normalise(x) for x in d.get("tags_include") or ()) if n),
            limit=int(d.get("limit", DEFAULT_LIMIT)),
            _keyset=frozenset(keys.compatible(code) if compat else [code]) if code else frozenset(),
        )

    def matches(
        self,
        t: Effective,
        track_id: str = "",
        vibe_members: dict[str, set[str]] | None = None,
        track_tags: frozenset[str] | None = None,
    ) -> bool:
        """True if a track's effective tags, vibe membership and community tags satisfy every constrained field."""
        if self.tags_include and (track_tags is None or not self.tags_include <= track_tags):
            return False
        if self.vibes_include:
            vm = vibe_members or {}
            if not all(track_id in vm.get(v, ()) for v in self.vibes_include):
                return False
        if self.families_include or self.family_styles or self.genres_include or self.styles_include:
            if not (
                t.family in self.families_include
                or any(t.family == f and t.primary_style in st for f, st in self.family_styles)
                or t.primary_genre in self.genres_include
                or t.primary_style in self.styles_include
            ):
                return False
        if self.genres_exclude and (t.primary_genre is None or t.primary_genre in self.genres_exclude):
            return False
        if self.styles_exclude and (t.primary_style is None or t.primary_style in self.styles_exclude):
            return False
        if self.bpm is not None:
            if t.bpm is None:
                return False
            b = t.bpm
            if not (self.bpm.contains(b) or (self.half_double and (self.bpm.contains(b * 2) or self.bpm.contains(b / 2)))):
                return False
        if self.energy is not None and (t.energy is None or not self.energy.contains(t.energy)):
            return False
        if self.moods_include or self.moods_exclude:
            if t.moods is None or not self.moods_include <= t.moods or self.moods_exclude & t.moods:
                return False
        if self.key is not None and t.key not in self._keyset:
            return False
        if self.vocal != "any":
            if t.vocal is None or t.vocal != (self.vocal == "vocal"):
                return False
        return True


def matching_ids(
    query: Query, tracks: dict[str, dict], overrides: dict[str, dict], vibe_members: dict[str, set[str]] | None = None
) -> list[str]:
    """IDs of all matching tracks, in sorted order. ``vibe_members`` comes from ``vibes.all_members``."""
    return sorted(
        tid
        for tid, tr in tracks.items()
        if query.matches(effective(tr, overrides.get(tid)), tid, vibe_members, tags.track_tags(tr))
    )


def run(
    query: Query,
    tracks: dict[str, dict],
    overrides: dict[str, dict],
    rng: random.Random | None = None,
    vibe_members: dict[str, set[str]] | None = None,
) -> list[str]:
    """Random sample of ``limit`` matches, shuffled (Fisher-Yates). Pass ``rng`` for deterministic tests."""
    rng = rng or random.Random()
    ids = matching_ids(query, tracks, overrides, vibe_members)
    sample = rng.sample(ids, min(query.limit, len(ids)))
    for i in range(len(sample) - 1, 0, -1):
        j = rng.randint(0, i)
        sample[i], sample[j] = sample[j], sample[i]
    return sample
