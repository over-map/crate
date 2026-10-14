"""Daily health check (build spec §14.3) -> ``data/health.json`` and ntfy alerts.

Each check returns a :class:`Check`. A failing check names the problem in plain words and the runbook anchor.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import asdict, dataclass
from datetime import UTC, date, datetime

STALE_SYNC_HOURS = 36
MIN_COVERAGE = 0.8
EXPIRY_WARN_DAYS = 14


@dataclass
class Check:
    name: str
    ok: bool
    message: str = ""
    runbook: str = ""
    warn: bool = False


def last_sync(state: dict, now: datetime) -> Check:
    ts = state.get("last_sync")
    if not ts:
        return Check("nightly", False, "Nightly sync has never succeeded", "nightly-failed")
    age = (now - datetime.fromisoformat(ts.replace("Z", "+00:00"))).total_seconds() / 3600
    if age > STALE_SYNC_HOURS:
        return Check("nightly", False, f"Last successful nightly sync was {age:.0f} h ago", "nightly-failed")
    return Check("nightly", True, f"Synced {age:.0f} h ago")


def coverage(library: dict) -> Check:
    tracks = library.get("tracks") or {}
    if not tracks:
        return Check("coverage", True, "Library is empty")
    tagged = sum(t.get("status") == "tagged" for t in tracks.values())
    pending = sum(t.get("status") == "pending" for t in tracks.values())
    share = tagged / len(tracks)
    msg = f"{tagged} of {len(tracks)} tagged ({share:.0%}), {pending} pending"
    if share < MIN_COVERAGE and pending == 0:
        return Check("coverage", False, f"Tag coverage is low: {msg}", "deezer-previews")
    return Check("coverage", True, msg)


def reachable(name: str, probe: Callable[[], object], anchor: str) -> Check:
    try:
        probe()
        return Check(name, True, "reachable")
    except Exception as e:
        return Check(name, False, f"{name} call failed: {str(e)[:120]}", anchor)


def expiry(name: str, expires_on: str | None, today: date, anchor: str) -> Check:
    """Credential expiry from a YYYY-MM-DD date (set as a repo variable when the credential is created)."""
    if not expires_on:
        return Check(f"{name} expiry", True, "no expiry date recorded")
    days = (date.fromisoformat(expires_on) - today).days
    if days < 0:
        return Check(f"{name} expiry", False, f"{name} expired {-days} days ago", anchor)
    if days <= EXPIRY_WARN_DAYS:
        return Check(f"{name} expiry", True, f"{name} expires in {days} days", anchor, warn=True)
    return Check(f"{name} expiry", True, f"{days} days left")


def report(
    checks: list[Check], state: dict, library: dict, credentials: list[dict] | None = None, now: datetime | None = None
) -> dict:
    """``data/health.json`` in the shape the app reads (CrateKit ``HealthFile``)."""
    now = now or datetime.now(UTC)
    problems = [
        {"id": c.name, "level": "warning" if c.ok else "broken", "message": c.message, "runbook": c.runbook}
        for c in checks
        if not c.ok or c.warn
    ]
    status = "broken" if any(p["level"] == "broken" for p in problems) else "warning" if problems else "healthy"
    tracks = library.get("tracks") or {}
    tagged = sum(t.get("status") == "tagged" for t in tracks.values())
    return {
        "schema": 1,
        "checked_at": now.replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "status": status,
        "last_nightly_success": state.get("last_sync"),
        "coverage": round(tagged / len(tracks), 3) if tracks else None,
        "problems": problems,
        "credentials": credentials or [],
        "checks": [asdict(c) for c in checks],
    }


def credential(name: str, expires_on: str | None, today: date) -> dict:
    return {
        "name": name,
        "expires_at": expires_on,
        "days_left": (date.fromisoformat(expires_on) - today).days if expires_on else None,
    }
