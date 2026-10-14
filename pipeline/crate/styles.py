"""Genre/style vocabulary and thresholds (Discogs-EffNet 400 ``Genre---Style`` labels).

Matching uses the strongest style only (docs/query-semantics.md). ``STYLE_THRESHOLD`` and
``DISPLAY_TOP`` choose which secondary styles the UI and report show. Tune both in M1.
"""

from __future__ import annotations

from .library import genre_of

STORED_TOP = 10  # styles kept in auto.styles
STYLE_THRESHOLD = 0.2
DISPLAY_TOP = 3
GENRE_THRESHOLD = 0.5


def top_styles(activations: dict[str, float], k: int = STORED_TOP) -> dict[str, float]:
    """The ``k`` strongest styles, rounded to 3 decimals."""
    ranked = sorted(activations.items(), key=lambda kv: (-kv[1], kv[0]))[:k]
    return {s: round(float(v), 3) for s, v in ranked}


def genres_from_styles(activations: dict[str, float]) -> dict[str, float]:
    """Sum of style activations per parent genre, normalised to sum to 1. Genres under 0.01 are dropped."""
    sums: dict[str, float] = {}
    for s, v in activations.items():
        sums[genre_of(s)] = sums.get(genre_of(s), 0.0) + float(v)
    total = sum(sums.values())
    if total <= 0:
        return {}
    return {g: round(v / total, 3) for g, v in sorted(sums.items(), key=lambda kv: -kv[1]) if v / total >= 0.01}


def displayed(styles: dict[str, float]) -> list[str]:
    """Styles worth showing: the strongest one always, then others ≥ STYLE_THRESHOLD, up to DISPLAY_TOP."""
    ranked = sorted(styles, key=lambda s: (-styles[s], s))
    return [s for i, s in enumerate(ranked[:DISPLAY_TOP]) if i == 0 or styles[s] >= STYLE_THRESHOLD]


def taxonomy(labels: list[str], aliases: dict[str, str] | None = None) -> dict:
    """``data/taxonomy.json`` from the model's label list."""
    genres: dict[str, list[str]] = {}
    for c in labels:
        genres.setdefault(genre_of(c), []).append(c)
    return {"schema": 1, "source": "genre_discogs400-discogs-effnet-1", "genres": genres, "aliases": aliases or {}}
