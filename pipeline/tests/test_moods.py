from crate import moods
from crate.library import MOOD_THRESHOLD


def test_cutoff_maps_to_shared_threshold():
    cuts = {"dark": 0.1, "party": 0.97, "groovy": None}
    out = moods.calibrate({"dark": 0.1, "party": 0.96, "groovy": 0.9}, cuts)
    assert out["dark"] == MOOD_THRESHOLD
    assert out["party"] < MOOD_THRESHOLD
    assert "groovy" not in out


def test_calibration_is_monotonic_and_bounded():
    cuts = {"dark": 0.1}
    vals = [moods.calibrate({"dark": p}, cuts).get("dark", 0) for p in (0.02, 0.05, 0.1, 0.4, 1.0)]
    assert vals == sorted(vals) and vals[-1] == 1.0


def test_recalibrate_from_raw():
    lib = {"tracks": {"a": {"auto": {"moods_raw": {"dark": 0.2}, "moods": {}}}, "b": {"status": "pending"}}}
    assert moods.recalibrate(lib, {"dark": 0.1}) == 1
    assert lib["tracks"]["a"]["auto"]["moods"]["dark"] > 0.5


def test_cutoffs_file_covers_every_mood():
    assert set(moods.cutoffs()) == set(moods.SOURCES)
