"""User-facing moods, the model output each one comes from, and per-mood calibration.

Every mood has a direct model label. Moods without one are dropped rather than invented (build spec §6.4):
- ``hypnotic`` is in the design but has no Essentia model, so it is not tagged automatically.
  It can still be added by hand in Edit tags (``moods_add``).

``vocal`` and ``instrumental`` are not moods: they come from ``voice_instrumental`` into ``auto.vocal``.
"""

from __future__ import annotations

import json
from pathlib import Path

# mood -> list of (model, label). Several sources are combined with max().
SOURCES: dict[str, list[tuple[str, str]]] = {
    "dark": [("mtg_jamendo_moodtheme", "dark")],
    "energetic": [("mtg_jamendo_moodtheme", "energetic")],
    "melancholic": [("mtg_jamendo_moodtheme", "melancholic")],
    "dreamy": [("mtg_jamendo_moodtheme", "dream")],
    "uplifting": [("mtg_jamendo_moodtheme", "uplifting")],
    "relaxing": [("mtg_jamendo_moodtheme", "relaxing"), ("mood_relaxed", "relaxed")],
    "epic": [("mtg_jamendo_moodtheme", "epic")],
    "romantic": [("mtg_jamendo_moodtheme", "romantic")],
    "sad": [("mtg_jamendo_moodtheme", "sad"), ("mood_sad", "sad")],
    "happy": [("mtg_jamendo_moodtheme", "happy"), ("mood_happy", "happy")],
    "party": [("mtg_jamendo_moodtheme", "party"), ("mood_party", "party")],
    "meditative": [("mtg_jamendo_moodtheme", "meditative")],
    "groovy": [("mtg_jamendo_moodtheme", "groovy")],
    "aggressive": [("mood_aggressive", "aggressive")],
}

MANUAL_ONLY = ("hypnotic",)
ALL = tuple(SOURCES) + MANUAL_ONLY

CUTOFFS_FILE = Path(__file__).resolve().parents[1] / "mood_cutoffs.json"


def cutoffs() -> dict[str, float | None]:
    """Per-mood cut-offs on the raw score, from mood_cutoffs.json (tuned by hand)."""
    return json.loads(CUTOFFS_FILE.read_text())["cutoffs"]


def combine(outputs: dict[str, dict[str, float]]) -> dict[str, float]:
    """Raw model outputs ``{model: {label: p}}`` -> raw ``{mood: score}`` (max over sources), keeping ≥ 0.01."""
    out: dict[str, float] = {}
    for mood, srcs in SOURCES.items():
        vals = [p for model, label in srcs if (p := (outputs.get(model) or {}).get(label)) is not None]
        if vals and max(vals) >= 0.01:
            out[mood] = round(float(max(vals)), 3)
    return dict(sorted(out.items(), key=lambda kv: -kv[1]))


def calibrate(raw: dict[str, float], cuts: dict[str, float | None]) -> dict[str, float]:
    """Map raw scores so each mood's cut-off lands on MOOD_THRESHOLD (0.5), piecewise linear.

    The query engine (Python and Swift) keeps one shared 0.5 threshold; per-mood tuning happens here.
    Moods with a null cut-off are left out (not tagged automatically).
    """
    out: dict[str, float] = {}
    for mood, p in raw.items():
        c = cuts.get(mood)
        if c is None or c <= 0 or c >= 1:
            continue
        v = 0.5 * p / c if p < c else 0.5 + 0.5 * (p - c) / (1 - c)
        if v >= 0.05:
            out[mood] = round(min(1.0, v), 3)
    return dict(sorted(out.items(), key=lambda kv: -kv[1]))


def recalibrate(library: dict, cuts: dict[str, float | None] | None = None) -> int:
    """Rewrite ``auto.moods`` from ``auto.moods_raw`` for every track. Returns how many tracks changed."""
    cuts = cuts if cuts is not None else cutoffs()
    changed = 0
    for t in (library.get("tracks") or {}).values():
        auto = t.get("auto") or {}
        if "moods_raw" not in auto:
            continue
        new = calibrate(auto["moods_raw"], cuts)
        if auto.get("moods") != new:
            auto["moods"] = new
            changed += 1
    return changed
