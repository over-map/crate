"""ntfy notifications: Weekly Mix ready, and plain-language failure alerts with a runbook link."""

from __future__ import annotations

import os

import httpx

RUNBOOK = "https://github.com/{repo}/blob/main/docs/runbook.md#{anchor}"


def send(
    topic: str | None,
    message: str,
    title: str = "Crate",
    click: str | None = None,
    priority: str = "default",
    transport: httpx.BaseTransport | None = None,
) -> bool:
    """POST to ntfy.sh/<topic>. Returns False (never raises) if the topic is unset or ntfy is down."""
    if not topic:
        return False
    headers = {"Title": title, "Priority": priority}
    if click:
        headers["Click"] = click
    try:
        with httpx.Client(transport=transport, timeout=15) as c:
            c.post(f"https://ntfy.sh/{topic}", content=message.encode(), headers=headers).raise_for_status()
        return True
    except httpx.HTTPError:
        return False


def alert(problem: str, anchor: str, topic: str | None = None) -> bool:
    """Failure alert, e.g. ``alert("Spotify login rejected", "spotify-token")``."""
    # The runbook lives in the public code repo; the workflows run in the private data repo.
    repo = os.environ.get("CRATE_CODE_REPO") or os.environ.get("GITHUB_REPOSITORY", "OWNER/crate")
    link = RUNBOOK.format(repo=repo, anchor=anchor)
    return send(
        topic or os.environ.get("NTFY_TOPIC"),
        f"{problem} → docs/runbook.md#{anchor}",
        title="Crate problem",
        click=link,
        priority="high",
    )
