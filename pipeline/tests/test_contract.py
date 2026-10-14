from crate.contract import missing_fields, run


def test_missing_fields():
    body = {"items": [{"track": {"id": "x", "artists": [{"name": "A"}]}}]}
    assert missing_fields(body, ["items[].track.id", "items[].track.artists[].name"]) == []
    assert missing_fields(body, ["items[].track.uri"]) == ["items[].track.uri"]
    assert missing_fields({"items": []}, ["items[].track.id"]) == []
    assert missing_fields({"items": {}}, ["items[].x"]) == ["items[].x"]


def test_run_reports_changes_and_failures():
    def boom():
        raise RuntimeError("down")

    problems = run({"spotify GET /me": lambda: {}, "deezer GET /search": boom})
    assert problems == ["Spotify API changed: spotify GET /me field id missing", "deezer GET /search failed: down"]
