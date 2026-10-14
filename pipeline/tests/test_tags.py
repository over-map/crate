import json
from pathlib import Path

import pytest

from crate.tags import normalise

CASES = json.loads((Path(__file__).resolve().parents[2] / "spec" / "tag_synonyms.json").read_text())["cases"]


@pytest.mark.parametrize("case", CASES, ids=[c["in"] or "<empty>" for c in CASES])
def test_normalise(case):
    assert normalise(case["in"]) == case["out"]


def test_merge_sources():
    from crate.tags import merge

    lib = {
        "tracks": {
            "a": {"artists": ["Lucio Dalla"]},
            "b": {"artists": ["Nobody Known"]},
            "c": {"artists": ["Kangding Ray"]},
            "d": {"artists": ["Silent"], "tags": ["stale"]},
        }
    }
    at = {
        "artists": {
            "Lucio Dalla": {"lastfm": ["Italy", "cantautori"]},
            "Kangding Ray": {"lastfm": ["minimal"]},
            "Silent": {"lastfm": []},
        }
    }
    bp = {"tracks": {"c": ["Techno (Raw / Deep / Hypnotic)"]}}
    dc = {"tracks": {"a": {"genres": ["Pop"], "styles": ["Chanson"]}}}
    assert merge(lib, at, bp, dc) == 3  # b had no tags and still has none
    t = lib["tracks"]
    assert t["a"]["tags"] == ["cantautori", "chanson", "italian", "pop"]
    assert "tags" not in t["b"]
    assert t["c"]["tags"] == ["minimal", "techno (raw deep hypnotic)"]
    assert t["d"]["tags"] == []
    assert t["a"]["tag_sources"] == {"lastfm": ["cantautori", "italian"], "discogs": ["chanson", "pop"]}
    assert merge(lib, at, bp, dc) == 0


def test_lastfm_filter():
    from crate.tags import lastfm_artist_tags

    raw = [{"name": "techno", "count": 100}, {"name": "seen live", "count": 9}, {"name": "dub", "count": 10}]
    assert lastfm_artist_tags(raw) == ["techno", "dub"]
