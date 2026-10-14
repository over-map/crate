from crate import energy


def test_deciles_spread_1_to_10():
    raw = {f"t{i:02d}": float(i) for i in range(20)}
    d = energy.deciles(raw)
    assert d["t00"] == 1 and d["t19"] == 10
    assert sorted(set(d.values())) == list(range(1, 11))
    assert sum(v == 5 for v in d.values()) == 2


def test_ties_share_energy():
    d = energy.deciles({"a": 1.0, "b": 1.0, "c": 2.0})
    assert d["a"] == d["b"]


def test_raw_weights_arousal_most():
    f = {
        "loud": {"arousal": 5, "loudness": -5, "onset_rate": 4, "danceability": 0.5},
        "arou": {"arousal": 7, "loudness": -10, "onset_rate": 4, "danceability": 0.5},
        "mid": {"arousal": 6, "loudness": -7.5, "onset_rate": 4, "danceability": 0.5},
    }
    r = energy.raw_scores(f)
    assert r["arou"] > r["loud"]


def test_recompute_writes_energy_and_raw():
    lib = {
        "tracks": {
            "a": {"auto": {"energy_features": {"arousal": 3, "loudness": -14, "onset_rate": 2, "danceability": 0.2}}},
            "b": {"auto": {"energy_features": {"arousal": 7, "loudness": -6, "onset_rate": 5, "danceability": 0.9}}},
            "c": {"status": "pending"},
        }
    }
    assert energy.recompute(lib) == 2
    assert lib["tracks"]["a"]["auto"]["energy"] == 1
    assert lib["tracks"]["b"]["auto"]["energy"] == 6
    assert "energy_raw" in lib["tracks"]["b"]["auto"]
    assert energy.recompute(lib) == 0
