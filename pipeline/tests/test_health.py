from datetime import UTC, date, datetime

from crate import health


def test_last_sync_stale():
    now = datetime(2026, 10, 12, 12, tzinfo=UTC)
    assert health.last_sync({"last_sync": "2026-10-12T03:00:00Z"}, now).ok
    c = health.last_sync({"last_sync": "2026-10-10T03:00:00Z"}, now)
    assert not c.ok and c.runbook == "nightly-failed"
    assert not health.last_sync({}, now).ok


def test_expiry_warns_then_fails():
    today = date(2026, 10, 12)
    assert health.expiry("PAT", "2027-01-01", today, "github-token").warn is False
    w = health.expiry("PAT", "2026-10-20", today, "github-token")
    assert w.ok and w.warn
    assert not health.expiry("PAT", "2026-10-01", today, "github-token").ok


def test_report_shape_matches_app():
    checks = [health.Check("nightly", True), health.Check("Spotify", False, "Spotify call failed", "spotify-token")]
    rep = health.report(checks, {"last_sync": "x"}, {"tracks": {"a": {"status": "tagged"}, "b": {"status": "pending"}}})
    assert rep["status"] == "broken"
    assert rep["coverage"] == 0.5
    assert rep["problems"] == [{"id": "Spotify", "level": "broken", "message": "Spotify call failed", "runbook": "spotify-token"}]
