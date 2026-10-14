from crate.report import render


def test_report_renders_sections():
    lib = {
        "generated_at": "now",
        "tracks": {
            f"t{i}": {
                "status": "tagged",
                "title": f"Song {i}",
                "artists": ["A"],
                "auto": {
                    "styles": {"Electronic---Techno": 0.6},
                    "bpm": 120 + i,
                    "bpm_conf": 0.3 if i == 0 else 0.9,
                    "key": "8A",
                    "key_conf": 0.8,
                    "energy": 1 + i % 10,
                    "moods": {"dark": 0.8},
                    "vocal": 0.1,
                },
            }
            for i in range(12)
        }
        | {"p": {"status": "pending", "title": "P"}},
    }
    gt = {"tracks": [{"id": "t1", "bpm": 121, "energy": 2, "style": "Electronic---Techno"}]}
    md = render(lib, {"tracks": {}}, ground_truth=gt)
    for h in (
        "## Coverage",
        "## Top 40 styles",
        "## BPM",
        "## Energy",
        "## Key",
        "## 40 random tracks",
        "Low-confidence tracks (1)",
        "## Ground truth",
    ):
        assert h in md
    assert "| Techno | Electronic | 12 |" in md
