import httpx
import pytest

from crate.http import Http, HttpError, QuotaExceeded, TokenBucket


def test_retry_after_then_success():
    calls, sleeps = [], []

    def handler(req):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(429, headers={"Retry-After": "3"}, text="slow down")
        return httpx.Response(200, json={"ok": True})

    h = Http(transport=httpx.MockTransport(handler), sleep=sleeps.append)
    assert h.get("https://api.spotify.com/v1/me") == {"ok": True}
    assert 3.0 in sleeps


def test_quota_exceeded_stops():
    h = Http(
        transport=httpx.MockTransport(lambda r: httpx.Response(429, json={"error": {"reason": "QUOTA_EXCEEDED"}})),
        sleep=lambda s: None,
    )
    with pytest.raises(QuotaExceeded):
        h.get("https://api.spotify.com/v1/me")


def test_5xx_backoff_then_give_up():
    h = Http(transport=httpx.MockTransport(lambda r: httpx.Response(503)), sleep=lambda s: None)
    with pytest.raises(HttpError):
        h.get("https://api.deezer.com/x")
    assert h.calls == 6


def test_4xx_raises_immediately():
    h = Http(transport=httpx.MockTransport(lambda r: httpx.Response(404, text="no")), sleep=lambda s: None)
    with pytest.raises(HttpError) as e:
        h.get("https://api.deezer.com/x")
    assert e.value.status == 404 and h.calls == 1


def test_disk_cache(tmp_path):
    n = []
    h = Http(cache_dir=tmp_path, transport=httpx.MockTransport(lambda r: (n.append(1), httpx.Response(200, json=[1]))[1]))
    assert h.get("https://api.deezer.com/a", cache=True) == [1]
    assert h.get("https://api.deezer.com/a", cache=True) == [1]
    assert len(n) == 1


def test_token_bucket_waits():
    t = [0.0]
    slept = []

    def sleep(s):
        slept.append(s)
        t[0] += s

    b = TokenBucket(2.0, clock=lambda: t[0], sleep=sleep)
    b.take()
    b.take()
    assert slept and abs(slept[0] - 0.5) < 1e-9
