import pytest

from crate.library import UnsupportedSchema, effective, load_json, save_json, upsert_saved


def item(i, **kw):
    return {"id": i, "uri": f"spotify:track:{i}", "title": i, "artists": ["A"], **kw}


def test_upsert_adds_pending_refreshes_and_removes():
    lib = {
        "tracks": {"old": {"status": "tagged", "title": "x"}, "keep": {"status": "tagged", "title": "stale", "auto": {"bpm": 1}}}
    }
    counts = upsert_saved(lib, [item("keep"), item("new")])
    assert counts == {"added": 1, "removed": 1, "total": 2}
    assert lib["tracks"]["new"]["status"] == "pending"
    assert lib["tracks"]["keep"]["title"] == "keep" and lib["tracks"]["keep"]["auto"] == {"bpm": 1}


def test_newer_schema_refused(tmp_path):
    p = tmp_path / "library.json"
    p.write_text('{"schema": 2, "tracks": {}}')
    with pytest.raises(UnsupportedSchema):
        load_json(p, {})


def test_save_reports_change(tmp_path):
    p = tmp_path / "x.json"
    assert save_json(p, {"a": 1})
    assert not save_json(p, {"a": 1})
    assert save_json(p, {"a": 2})


def test_genres_add_overrides_genre():
    e = effective({"auto": {"styles": {"Electronic---Techno": 0.6}}}, {"genres_add": ["Rock"]})
    assert e.primary_genre == "Rock" and e.primary_style is None


def test_moods_unknown_vs_empty():
    assert effective({"auto": {}}).moods is None
    assert effective({"auto": {"moods": {}}}).moods == frozenset()
