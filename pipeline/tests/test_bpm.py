import json
from pathlib import Path

from crate.bpm import resolve


def test_no_sources():
    assert resolve() is None
    assert resolve(0, None, 0) is None


def test_all_agree():
    r = resolve(138.2, 137.9, 138)
    assert r.bpm == 138.0
    assert r.confidence > 0.6
    assert r.candidates == [138.0, 69.0]


def test_two_audio_estimators_beat_doubled_deezer():
    # Massive Attack "Teardrop", Oct 2026: TempoCNN 77, RhythmExtractor 76.95, Deezer 154.27
    assert resolve(77.0, 76.95, 154.27, danceability=0.79).bpm == 77.0


def test_half_time_tempocnn_loses_to_rhythm_on_dance_track():
    assert resolve(69.0, 138.0, None, danceability=0.99).bpm == 138.0


def test_danceable_slow_estimate_moves_up_an_octave():
    # sources disagree on the octave; a very danceable track tips it to the faster one
    assert resolve(86.0, 172.0, 86.0, danceability=0.99).bpm == 172.0
    assert resolve(86.0, 172.0, 86.0, danceability=0.5).bpm == 86.0


def test_rounding_to_half():
    assert resolve(127.8, None, None).bpm == 128.0
    assert resolve(127.3, None, None).bpm == 127.5


def test_hiphop_stays_slow_when_sources_agree():
    assert resolve(90.0, 90.2, 90.0, danceability=0.8).bpm == 90.0


def test_ground_truth_if_present():
    """Spec §10: ≥ 90 % within ±2 % once spec/ground_truth.json exists (M1)."""
    gt = Path(__file__).resolve().parents[2] / "spec" / "ground_truth.json"
    if not gt.exists():
        return
    rows = [r for r in json.loads(gt.read_text())["tracks"] if r.get("estimates")]
    if not rows:
        return
    ok = sum(abs(resolve(**r["estimates"]).bpm - r["bpm"]) <= 0.02 * r["bpm"] for r in rows)
    assert ok / len(rows) >= 0.9
