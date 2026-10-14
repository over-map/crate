from crate import answerkey


def test_bpm_range_names():
    assert answerkey.bpm_range("BPM -80") == (0, 80)
    assert answerkey.bpm_range("BPM 090-100") == (90, 100)
    assert answerkey.bpm_range("BPM 160") == (160, float("inf"))
    assert answerkey.bpm_range("FI_Reggae") is None


def test_grade():
    backup = {
        "playlists": [
            {
                "name": "BPM 120-130",
                "items": [{"uri": "spotify:track:a"}, {"uri": "spotify:track:b"}, {"uri": "spotify:track:c"}],
            },
            {"name": "FI_Reggae", "items": [{"uri": "spotify:track:a"}, {"uri": "spotify:track:b"}]},
            {"name": "Rime25", "items": [{"uri": "spotify:track:c"}]},
        ]
    }
    lib = {
        "tracks": {
            "a": {"status": "tagged", "auto": {"bpm": 131.0, "styles": {"Reggae---Dub": 0.5}}},
            "b": {"status": "tagged", "auto": {"bpm": 62.5, "styles": {"Electronic---Techno": 0.5}}},
            "c": {"status": "tagged", "auto": {"bpm": 150.0}},
        }
    }
    r = answerkey.grade(lib, {}, backup)
    assert r["bpm"] == {"ok": 1, "octave": 1, "wrong": 1}
    assert r["genre"]["FI_Reggae"] == {"ok": 1, "wrong": 1}
    md = answerkey.render(r, lib["tracks"])
    assert "33% in the right bucket" in md and "| FI_Reggae | 2 | 50% |" in md
