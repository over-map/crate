import json

import pytest

from crate import keys


def test_camelot_vectors(spec_dir):
    v = json.loads((spec_dir / "camelot_vectors.json").read_text())
    for k in v["keys"]:
        assert keys.to_camelot(k["tonic"], k["scale"]) == k["camelot"], k
    for n in v["names"]:
        assert keys.name(n["camelot"]) == n["name"]
    for c in v["compatible"]:
        assert keys.compatible(c["camelot"]) == c["set"]
    for bad in v["invalid"]:
        assert not keys.is_camelot(bad)
        with pytest.raises(ValueError):
            keys.compatible(bad)


def test_unknown_key_raises():
    with pytest.raises(ValueError):
        keys.to_camelot("H", "minor")
