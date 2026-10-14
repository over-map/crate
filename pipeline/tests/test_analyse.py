import numpy as np

from crate.analyse import AudioFeatures, analyse_pending, auto_tags
from crate.deezer import DeezerTrack


def feats():
    styles = {f"Electronic---S{i}": 0.01 for i in range(20)}
    styles.update({"Electronic---Techno": 0.6, "Rock---Industrial": 0.3})
    return AudioFeatures(
        styles=styles,
        embedding=np.ones(1280, np.float32),
        mood_outputs={"mtg_jamendo_moodtheme": {"dark": 0.7, "dream": 0.2}, "mood_aggressive": {"aggressive": 0.9}},
        vocal=0.2,
        danceability=0.8,
        arousal=6.0,
        loudness_lufs=-8.0,
        onset_rate=4.0,
        tempocnn_bpm=128.0,
        rhythm_bpm=128.2,
        key_tonic="A",
        key_scale="minor",
        key_strength=0.7,
    )


def test_auto_tags():
    a = auto_tags(feats(), 128.0)
    assert list(a["styles"])[0] == "Electronic---Techno" and len(a["styles"]) == 10
    assert a["bpm"] == 128.0 and a["key"] == "8A"
    assert a["moods_raw"] == {"aggressive": 0.9, "dark": 0.7, "dreamy": 0.2}
    assert set(a["moods"]) == {"aggressive", "dark", "dreamy"} and all(v >= 0.5 for v in a["moods"].values())
    assert "hypnotic" not in a["moods"]
    assert set(a["energy_features"]) == {"arousal", "loudness", "onset_rate", "danceability"}


class FakeHttp:
    def download(self, url, dest):
        dest.write_bytes(b"mp3")
        return dest


class FakeDeezer:
    http = FakeHttp()

    def match(self, isrc, title, artists, duration_ms):
        return {
            "p": DeezerTrack(1, "https://p", 128.0, 200, 9, isrc),
            "np": DeezerTrack(2, None, 140.0, 200, 9, isrc),
            "boom": DeezerTrack(3, "https://p", None, 200, 9, isrc),
        }.get(isrc)


class FakeAnalyzer:
    def __init__(self):
        self.paths = []

    def analyse(self, path):
        self.paths.append(path)
        if "t3" in path.name:
            raise RuntimeError("decode failed")
        return feats()


def test_analyse_pending_statuses_and_cleanup():
    lib = {
        "tracks": {
            "t1": {"status": "pending", "isrc": "p", "liked_at": "2024"},
            "t2": {"status": "pending", "isrc": "np", "liked_at": "2023"},
            "t3": {"status": "pending", "isrc": "boom", "liked_at": "2025"},
            "t4": {"status": "pending", "isrc": "none", "liked_at": "2022"},
            "t5": {"status": "tagged", "isrc": "p"},
        }
    }
    embs = {}
    an = FakeAnalyzer()
    res = analyse_pending(lib, FakeDeezer(), an, "v1", embs.__setitem__)
    t = lib["tracks"]
    assert (res.tagged, res.no_preview, res.errors) == (1, 2, 1)
    assert t["t1"]["status"] == "tagged" and t["t1"]["source"]["model_version"] == "v1"
    assert t["t2"]["status"] == "no_preview" and t["t2"]["auto"]["bpm"] == 140.0
    assert t["t4"]["status"] == "no_preview" and "auto" not in t["t4"]
    assert t["t3"]["status"] == "error"
    assert t["t5"]["status"] == "tagged" and "source" not in t["t5"]
    assert list(embs) == ["t1"]
    assert all(not p.exists() for p in an.paths)


def test_max_and_force():
    lib = {
        "tracks": {
            "a": {"status": "pending", "isrc": "p", "liked_at": "1"},
            "b": {"status": "pending", "isrc": "p", "liked_at": "2"},
            "c": {"status": "tagged", "isrc": "p"},
        }
    }
    res = analyse_pending(lib, FakeDeezer(), FakeAnalyzer(), "v", lambda *a: None, max_tracks=1, force={"c"})
    assert res.tagged == 1 and lib["tracks"]["b"]["status"] == "pending"
