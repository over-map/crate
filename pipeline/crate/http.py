"""One shared HTTP client with per-host rate limits, retries and an on-disk response cache."""

from __future__ import annotations

import hashlib
import json
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import httpx

# Requests per second, per host (build spec §6.2).
RATES = {
    "api.spotify.com": 3.0,
    "accounts.spotify.com": 1.0,
    "api.deezer.com": 8.0,
    "ws.audioscrobbler.com": 4.0,
    "api.listenbrainz.org": 2.0,
    "api.discogs.com": 0.9,  # 60 requests/minute with a token
}
DEFAULT_RATE = 4.0
MAX_RETRIES = 5
MAX_RETRY_AFTER_S = 120


class QuotaExceeded(RuntimeError):
    """Spotify said the app's quota is used up. Stop the run cleanly; the next schedule resumes."""


class HttpError(RuntimeError):
    def __init__(self, status: int, url: str, body: str):
        super().__init__(f"HTTP {status} for {url}: {body[:200]}")
        self.status = status
        self.url = url
        self.body = body


@dataclass
class TokenBucket:
    rate: float
    clock: Callable[[], float] = time.monotonic
    sleep: Callable[[float], None] = time.sleep
    _tokens: float = field(default=1.0, init=False)
    _last: float | None = field(default=None, init=False)

    def take(self) -> None:
        now = self.clock()
        if self._last is not None:
            self._tokens = min(1.0, self._tokens + (now - self._last) * self.rate)
        self._last = now
        if self._tokens < 1.0:
            wait = (1.0 - self._tokens) / self.rate
            self.sleep(wait)
            self._last = self.clock()
            self._tokens = 1.0
        self._tokens -= 1.0


class Http:
    """Thin wrapper around ``httpx.Client``. ``transport`` and ``sleep`` are injectable for tests."""

    def __init__(
        self,
        cache_dir: Path | None = None,
        transport: httpx.BaseTransport | None = None,
        sleep: Callable[[float], None] = time.sleep,
        timeout: float = 30.0,
    ):
        self.client = httpx.Client(transport=transport, timeout=timeout, follow_redirects=True)
        self.cache_dir = cache_dir
        self.sleep = sleep
        self.buckets: dict[str, TokenBucket] = {}
        self.calls = 0

    def _bucket(self, host: str) -> TokenBucket:
        if host not in self.buckets:
            self.buckets[host] = TokenBucket(RATES.get(host, DEFAULT_RATE), sleep=self.sleep)
        return self.buckets[host]

    def _cache_path(self, url: str, params: dict | None) -> Path | None:
        if self.cache_dir is None:
            return None
        key = hashlib.sha256((url + json.dumps(params or {}, sort_keys=True)).encode()).hexdigest()
        return self.cache_dir / key[:2] / f"{key}.json"

    def request(
        self,
        method: str,
        url: str,
        *,
        params: dict | None = None,
        json_body: Any = None,
        data: dict | None = None,
        headers: dict | None = None,
        auth: tuple[str, str] | None = None,
        cache: bool = False,
    ) -> Any:
        """Send a request and return the decoded JSON body (or None for an empty body).

        429: honours Retry-After; ``QUOTA_EXCEEDED`` raises :class:`QuotaExceeded`.
        5xx and network errors: exponential backoff. Other 4xx raise :class:`HttpError`.
        """
        cpath = self._cache_path(url, params) if cache and method == "GET" else None
        if cpath and cpath.exists():
            return json.loads(cpath.read_text())

        host = urlsplit(url).hostname or ""
        delay = 1.0
        for attempt in range(MAX_RETRIES + 1):
            self._bucket(host).take()
            self.calls += 1
            try:
                r = self.client.request(method, url, params=params, json=json_body, data=data, headers=headers, auth=auth)
            except httpx.TransportError:
                if attempt == MAX_RETRIES:
                    raise
                self.sleep(delay)
                delay *= 2
                continue
            if r.status_code == 429:
                if "QUOTA_EXCEEDED" in r.text:
                    raise QuotaExceeded(url)
                if attempt == MAX_RETRIES:
                    raise HttpError(429, url, r.text)
                self.sleep(min(float(r.headers.get("Retry-After", delay)), MAX_RETRY_AFTER_S))
                delay *= 2
                continue
            if r.status_code >= 500:
                if attempt == MAX_RETRIES:
                    raise HttpError(r.status_code, url, r.text)
                self.sleep(delay)
                delay *= 2
                continue
            if r.status_code >= 400:
                raise HttpError(r.status_code, url, r.text)
            body = r.json() if r.content else None
            if cpath is not None:
                cpath.parent.mkdir(parents=True, exist_ok=True)
                cpath.write_text(json.dumps(body))
            return body
        raise AssertionError("unreachable")

    def get(self, url: str, **kw: Any) -> Any:
        return self.request("GET", url, **kw)

    def download(self, url: str, dest: Path) -> Path:
        """Stream a file (e.g. a Deezer preview) to ``dest``."""
        host = urlsplit(url).hostname or ""
        self._bucket(host).take()
        with self.client.stream("GET", url) as r:
            if r.status_code >= 400:
                raise HttpError(r.status_code, url, "")
            with dest.open("wb") as f:
                for chunk in r.iter_bytes():
                    f.write(chunk)
        return dest
