import httpx

from crate.deezer import Deezer, is_match, normalise
from crate.http import Http


def test_normalise():
    assert normalise("Teardrop (Remastered 2019)") == "teardrop"
    assert normalise("Café del Mar - 2011 Remaster") == "cafe del mar"
    assert normalise("Song feat. Someone") == "song"
    assert normalise("Rock & Roll") == "rock and roll"


def cand(title, artist, duration):
    return {"title": title, "artist": {"name": artist}, "duration": duration}


def test_match_duration_tolerance():
    assert is_match("Teardrop", ["Massive Attack"], 330_000, cand("Teardrop (Remastered 2019)", "Massive Attack", 332))
    assert not is_match("Teardrop", ["Massive Attack"], 330_000, cand("Teardrop", "Massive Attack", 334))


def test_match_needs_artist_and_title():
    assert not is_match("Teardrop", ["Massive Attack"], None, cand("Teardrop", "Newton Faulkner", 330))
    assert not is_match("Angel", ["Massive Attack"], None, cand("Teardrop", "Massive Attack", 330))
    assert is_match("Angel", ["Someone", "Massive Attack"], None, cand("Angel", "Massive Attack", 1))


def fake(routes):
    def handler(req: httpx.Request):
        for key, body in routes.items():
            if key in str(req.url):
                return httpx.Response(200, json=body)
        return httpx.Response(200, json={"error": {"code": 800}})

    return Http(transport=httpx.MockTransport(handler), sleep=lambda s: None)


def test_isrc_then_search_fallback():
    dz = Deezer(
        fake(
            {
                "isrc:GB1": {"id": 1, "preview": "https://p/1.mp3", "bpm": 0, "duration": 300, "isrc": "GB1"},
                "/search": {"data": [{"id": 2, "title": "Song", "artist": {"name": "Band"}, "duration": 200}]},
                "/track/2": {"id": 2, "preview": "https://p/2.mp3", "bpm": 128.0, "duration": 200, "isrc": "US2"},
            }
        )
    )
    t = dz.match("GB1", "x", ["y"], None)
    assert t.id == 1 and t.bpm is None
    t2 = dz.match("NOPE", "Song", ["Band"], 201_000)
    assert t2.id == 2 and t2.bpm == 128.0
