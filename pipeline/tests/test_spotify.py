import json

import httpx
import pytest

from crate.http import Http
from crate.spotify import PlaylistWriter, Spotify, find_or_create


class Recorder:
    def __init__(self, routes=None):
        self.requests = []
        self.routes = routes or {}

    def __call__(self, req: httpx.Request):
        self.requests.append(req)
        if "accounts.spotify.com" in str(req.url):
            return httpx.Response(200, json={"access_token": "A", "refresh_token": "NEW"})
        for k, v in self.routes.items():
            if k in str(req.url):
                return httpx.Response(200, json=v)
        return httpx.Response(200, json={})


def client(rec):
    return Spotify(Http(transport=httpx.MockTransport(rec), sleep=lambda s: None), "id", "secret", "OLD")


def test_refresh_rotation_is_reported():
    rec = Recorder({"/me": {"id": "me"}})
    sp = client(rec)
    sp.me()
    assert sp.rotated_refresh_token == "NEW"


def test_writer_refuses_other_playlists():
    sp = client(Recorder())
    with pytest.raises(PermissionError):
        PlaylistWriter(sp, {"now"}).replace("someone-else", ["spotify:track:1"])


def test_writer_put_then_post_batches():
    rec = Recorder()
    sp = client(rec)
    PlaylistWriter(sp, {"now"}).replace("now", [f"spotify:track:{i}" for i in range(250)])
    writes = [r for r in rec.requests if "/playlists/now/items" in str(r.url)]
    assert [r.method for r in writes] == ["PUT", "POST", "POST"]
    assert [len(json.loads(r.content)["uris"]) for r in writes] == [100, 100, 50]


def test_writer_empty_result_clears_playlist():
    rec = Recorder()
    PlaylistWriter(client(rec), {"now"}).replace("now", [])
    writes = [r for r in rec.requests if "/items" in str(r.url)]
    assert len(writes) == 1 and json.loads(writes[0].content) == {"uris": []}


def test_library_calls_batch_40_in_query():
    rec = Recorder({"/me/library/contains": [True] * 40})
    sp = client(rec)
    sp.save_to_library([f"spotify:track:{i}" for i in range(41)])
    puts = [r for r in rec.requests if r.method == "PUT"]
    assert len(puts) == 2
    assert puts[0].url.params["uris"].count(",") == 39


def test_saved_tracks_paging_and_identity():
    page1 = {
        "items": [
            {
                "added_at": "2024-01-01T00:00:00Z",
                "track": {
                    "id": "t1",
                    "uri": "spotify:track:t1",
                    "name": "Song",
                    "artists": [{"name": "A"}],
                    "duration_ms": 1000,
                    "external_ids": {"isrc": "GB1"},
                    "album": {"images": [{"url": "https://i/1"}]},
                },
            }
        ],
        "next": "https://api.spotify.com/v1/me/tracks?offset=50",
    }
    page2 = {"items": [{"added_at": "x", "track": {"id": None}}], "next": None}

    class R(Recorder):
        def __call__(self, req):
            self.requests.append(req)
            if "accounts" in str(req.url):
                return httpx.Response(200, json={"access_token": "A"})
            return httpx.Response(200, json=page2 if "offset=50" in str(req.url) else page1)

    out = list(client(R()).saved_tracks())
    assert out == [
        {
            "id": "t1",
            "uri": "spotify:track:t1",
            "isrc": "GB1",
            "title": "Song",
            "artists": ["A"],
            "duration_ms": 1000,
            "cover": "https://i/1",
            "liked_at": "2024-01-01T00:00:00Z",
        }
    ]


def test_find_or_create_prefers_owned_by_name():
    rec = Recorder(
        {
            "/me/playlists": {
                "items": [
                    {"id": "x", "name": "Now", "owner": {"id": "other"}},
                    {"id": "mine", "name": "Now", "owner": {"id": "me"}},
                ],
                "next": None,
            }
        }
    )
    assert find_or_create(client(rec), "Now", None, "me") == "mine"
    assert find_or_create(client(rec), "Now", "known", "me") == "known"
