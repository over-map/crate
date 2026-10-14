import json
import random
from pathlib import Path

import pytest

from crate import tags, vibes
from crate.query import Query, matching_ids, run

VECTORS_FILE = json.loads((Path(__file__).resolve().parents[2] / "spec" / "query_vectors.json").read_text())
TRACKS = VECTORS_FILE["tracks"]
OVERRIDES = {tid: t["override"] for tid, t in TRACKS.items() if "override" in t}
VECS = {tid: t["vector"] for tid, t in TRACKS.items() if "vector" in t}
ARTISTS = {tid: t.get("artists", []) for tid, t in TRACKS.items()}
TRACK_TAGS = {tid: tags.track_tags(t) for tid, t in TRACKS.items()}
ENERGY = vibes.energies(TRACKS, OVERRIDES)
MEMBERS = vibes.all_members(VECTORS_FILE.get("vibes", {}), ARTISTS, VECS, TRACK_TAGS, ENERGY)


@pytest.mark.parametrize("case", VECTORS_FILE["cases"], ids=[c["name"] for c in VECTORS_FILE["cases"]])
def test_vector(case):
    q = Query.from_json(case["query"])
    assert matching_ids(q, TRACKS, OVERRIDES, MEMBERS) == case["matches"]
    for seed in range(3):
        result = run(q, TRACKS, OVERRIDES, random.Random(seed), MEMBERS)
        assert len(result) == case["count"]
        assert len(set(result)) == len(result)
        assert set(result) <= set(case["matches"])


def test_there_are_at_least_50_vectors():
    assert len(VECTORS_FILE["cases"]) >= 50


def test_run_is_deterministic_with_seed_and_shuffles():
    q = Query.from_json({})
    a = run(q, TRACKS, OVERRIDES, random.Random(1))
    b = run(q, TRACKS, OVERRIDES, random.Random(1))
    assert a == b
    assert a != sorted(a)


def test_bad_query_rejected():
    with pytest.raises(ValueError):
        Query.from_json({"key": {"camelot": "13A"}})
    with pytest.raises(ValueError):
        Query.from_json({"vocal": "maybe"})


def test_default_share_constant_matches():
    assert VECTORS_FILE["constants"]["VIBE_DEFAULT_SHARE"] == vibes.DEFAULT_SHARE


def test_negative_weight_constant_matches():
    assert VECTORS_FILE["constants"]["VIBE_NEGATIVE_WEIGHT"] == vibes.NEGATIVE_WEIGHT
