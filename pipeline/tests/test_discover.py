import numpy as np

from crate import discover, taste
from crate.discover import Candidate


def unit(v):
    v = np.asarray(v, dtype=float)
    return v / np.linalg.norm(v)


def test_multiplier_is_neutral_for_new_families_and_bounded():
    assert discover.multiplier(0, 0) == 1.0
    assert discover.multiplier(20, 15) > 2
    assert discover.multiplier(40, 0) == discover.MULTIPLIER_RANGE[0]


def test_taste_families_ignore_library_size():
    counts = {"house": 225, "techno": 222, "hip hop": 41, "jazz": 13, "classical": 1}
    picked = Counter_of_runs(lambda rng: discover.taste_families(counts, {}, rng, n=1), runs=4000)
    assert "classical" not in picked  # < 3 tracks: not a taste family
    share = {f: picked[f] / 4000 for f in ("house", "techno", "hip hop", "jazz")}
    assert all(0.2 < s < 0.3 for s in share.values()), share  # ~1/4 each, not proportional to size


def test_discovery_prefers_small_and_absent_families():
    counts = {"house": 225, "hip hop": 41}
    fams = ["house", "hip hop", "jazz"]
    picked = Counter_of_runs(lambda rng: list(discover.discovery_slots(counts, fams, {}, rng, 1)), runs=3000)
    assert picked["jazz"] > picked["hip hop"] > picked["house"] > 0


def test_discovery_slots_respect_cap():
    rng = np.random.default_rng(0)
    out = discover.discovery_slots({}, ["a", "b", "c", "d"], {}, rng, 10)
    assert sum(out.values()) == 10 and max(out.values()) <= discover.MAX_DISCOVERY_PER_FAMILY


def test_split_default_and_learning():
    assert discover.split({"items": []}) == 10
    liked_disc = [{"kind": "discovery", "liked": True}] * 20 + [{"kind": "taste", "liked": False}] * 20
    assert discover.split({"items": liked_disc}) == discover.DISCOVERY_RANGE[1]
    ignored_disc = [{"kind": "discovery", "liked": False}] * 20 + [{"kind": "taste", "liked": True}] * 20
    assert discover.split({"items": ignored_disc}) == discover.DISCOVERY_RANGE[0]


def test_family_stats():
    fb = {"items": [{"family": "jazz", "liked": True}, {"family": "jazz", "liked": False}, {"liked": True}]}
    assert discover.family_stats(fb) == {"jazz": (2, 1)}


def test_score_taste_vs_discovery():
    cands = [Candidate("a", "x", unit([1, 0, 0]), "house", "taste"), Candidate("b", "y", unit([0, 1, 0]), "hip hop", "discovery")]
    s = discover.score(cands, {"house": np.array([1.0, 0, 0])}, np.stack([unit([0, 1, 0]), unit([0, 0, 1])]))
    assert np.allclose(s, [1.0, 1.0])


def test_select_fills_slots_caps_artists_and_tops_up():
    cands = [Candidate(f"h{i}", f"art{i % 2}", unit([1, i / 10, 0]), "house", "taste") for i in range(6)]
    cands += [Candidate(f"j{i}", f"j{i}", unit([0, 1, i / 10]), "jazz", "discovery") for i in range(1)]
    cands += [Candidate(f"r{i}", f"r{i}", unit([0, 0, 1]), "hip hop", "discovery") for i in range(3)]
    scores = np.linspace(1, 0.1, len(cands))
    slots = {("taste", "house"): 3, ("discovery", "jazz"): 2, ("discovery", "hip hop"): 1}
    picks = discover.select(cands, scores, slots, size=6)
    assert len(picks) == 6
    assert sum(p.kind == "taste" for p in picks) == 3 and sum(p.kind == "discovery" for p in picks) == 3
    artists = [next(c.artist for c in cands if c.key == p.key) for p in picks]
    assert max(artists.count(a) for a in artists) <= 2
    assert {p.family for p in picks if p.kind == "discovery"} == {"jazz", "hip hop"}  # jazz short: topped up


def test_profile_finds_clusters():
    rng = np.random.default_rng(0)
    centers = np.eye(8)[:3]
    x = np.vstack([c + rng.normal(0, 0.05, (40, 8)) for c in centers])
    x /= np.linalg.norm(x, axis=1, keepdims=True)
    prof, _ = taste.profile(x, k_range=range(2, 6))
    assert len(prof.weights) == 3


def test_logistic_learns_direction():
    rng = np.random.default_rng(0)
    x = rng.normal(size=(200, 4))
    y = (x[:, 0] > 0).astype(float)
    w = taste.logistic(x, y, np.ones(200))
    p = taste.predict(w, np.array([[2, 0, 0, 0], [-2, 0, 0, 0]]))
    assert p[0] > 0.8 and p[1] < 0.2


def Counter_of_runs(fn, runs):
    from collections import Counter

    c = Counter()
    for i in range(runs):
        c.update(fn(np.random.default_rng(i)))
    return c


def test_discovery_pick_relabelled_by_its_own_sound():
    from types import SimpleNamespace

    from crate.weekly import own_family

    feats = SimpleNamespace(styles={"Funk / Soul---Contemporary R&B": 0.6, "Electronic---Dubstep": 0.2})
    assert own_family(feats, "breaks & bass", "discovery") == "funk & soul"
    assert own_family(feats, "breaks & bass", "taste") == "breaks & bass"


def test_reason_text():
    from crate.weekly import reason

    assert (
        reason("taste", "latin & brazilian", {"latin & brazilian": 18})
        == "Your taste · Latin & Brazilian: sounds like your 18 latin & brazilian tracks"
    )
    assert reason("discovery", "classical", {}).startswith("Discovery · Classical: you have none yet")
    assert "1 track," in reason("discovery", "jazz", {"jazz": 1})


def test_daily_labels_and_recent_window():
    from datetime import UTC, datetime

    from crate.weekly import day_label, recent_keys

    now = datetime(2026, 10, 13, 4, tzinfo=UTC)
    assert day_label(now) == "2026-10-13"
    history = [
        {"day": "2026-10-12", "keys": ["a"]},
        {"day": "2026-09-01", "keys": ["old"]},
        {"week": "2026-W42", "keys": ["legacy"]},
    ]
    assert recent_keys(history, now) == {"a", "legacy"}
