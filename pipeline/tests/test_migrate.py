import pytest

from crate import migrate


def backup():
    return {
        "playlists": [
            {
                "id": "p1",
                "name": "Techno",
                "owned": True,
                "items": [
                    {"uri": "spotify:track:a", "type": "track"},
                    {"uri": "spotify:track:b", "type": "track"},
                    {"uri": "spotify:local:x:y:z:1", "type": "track", "is_local": True},
                    {"uri": "spotify:episode:e", "type": "episode"},
                    {"uri": "spotify:track:dead", "type": "track", "is_playable": False},
                ],
            },
            {
                "id": "p2",
                "name": "Collab",
                "owned": True,
                "collaborative": True,
                "items": [{"uri": "spotify:track:b", "type": "track"}, {"uri": "spotify:track:c", "type": "track"}],
            },
            {"id": "f1", "name": "Discover Weekly", "owned": False},
            {"id": "now1", "name": "Now", "owned": True, "items": [{"uri": "spotify:track:z", "type": "track"}]},
        ]
    }


def test_plan():
    p = migrate.plan(backup(), liked={"spotify:track:c"}, crate_playlist_ids={"now1"})
    assert p.to_like == ["spotify:track:a", "spotify:track:b"]
    assert p.already_liked == ["spotify:track:c"]
    assert set(p.skipped) == {"local file", "episode", "unavailable", "crate playlist"}
    assert [x["id"] for x in p.delete_owned] == ["p1", "p2"]
    assert [x["id"] for x in p.unfollow] == ["f1"]
    assert len(p.code) == 6


def test_code_changes_when_plan_changes():
    a = migrate.plan(backup(), set(), set())
    b = migrate.plan(backup(), {"spotify:track:a"}, set())
    assert a.code != b.code
    assert migrate.plan(backup(), set(), set()).code == a.code


class FakeSpotify:
    def __init__(self, missing=()):
        self.saved, self.removed, self.missing = [], [], set(missing)

    def save_to_library(self, uris):
        self.saved += uris

    def contains(self, uris):
        return {u: u not in self.missing for u in uris}

    def remove_from_library(self, uris):
        self.removed += uris


def test_execute_requires_code(tmp_path):
    p = migrate.plan(backup(), set(), {"now1"})
    with pytest.raises(PermissionError):
        migrate.execute(FakeSpotify(), p, "WRONG1", migrate.Checkpoint.load(tmp_path / "c.json"))


def test_execute_likes_verifies_deletes(tmp_path):
    p = migrate.plan(backup(), set(), {"now1"})
    sp = FakeSpotify()
    migrate.execute(sp, p, p.code.lower(), migrate.Checkpoint.load(tmp_path / "c.json"))
    assert sp.saved == ["spotify:track:a", "spotify:track:b", "spotify:track:c"]
    assert sp.removed == ["spotify:playlist:p1", "spotify:playlist:p2", "spotify:playlist:f1"]


def test_execute_aborts_delete_if_missing(tmp_path):
    p = migrate.plan(backup(), set(), {"now1"})
    sp = FakeSpotify(missing={"spotify:track:b"})
    with pytest.raises(migrate.MissingAfterLike):
        migrate.execute(sp, p, p.code, migrate.Checkpoint.load(tmp_path / "c.json"))
    assert sp.removed == []


def test_execute_resumes_from_checkpoint(tmp_path):
    p = migrate.plan(backup(), set(), {"now1"})
    cp = migrate.Checkpoint(tmp_path / "c.json", liked=["spotify:track:a"], removed=["p1"])
    cp.save()
    sp = FakeSpotify()
    migrate.execute(sp, p, p.code, migrate.Checkpoint.load(tmp_path / "c.json"))
    assert sp.saved == ["spotify:track:b", "spotify:track:c"]
    assert "spotify:playlist:p1" not in sp.removed
