"""Essentia model files: download once, verify against models.lock.json, cache locally.

Upgrading a model is never automatic: run ``crate report`` before and after, then bump the lock by hand.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import httpx

LOCK = Path(__file__).resolve().parents[1] / "models.lock.json"
# Repo-local, git-ignored (crate/.cache/models). Override with CRATE_MODELS_DIR.
DEFAULT_DIR = Path(os.environ.get("CRATE_MODELS_DIR", Path(__file__).resolve().parents[2] / ".cache" / "models"))
# Mirror kept as a GitHub Release asset so the system survives upstream URLs disappearing (spec §14.2).
MIRROR_ENV = "CRATE_MODELS_MIRROR"


class ChecksumMismatch(RuntimeError):
    pass


def lock() -> dict:
    return json.loads(LOCK.read_text())


def model_version() -> str:
    return lock()["model_version"]


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ensure(directory: Path = DEFAULT_DIR) -> Path:
    """Make sure every locked model file is present and matches its checksum. Returns the directory."""
    spec = lock()
    directory.mkdir(parents=True, exist_ok=True)
    mirror = os.environ.get(MIRROR_ENV)
    for name, meta in spec["files"].items():
        dest = directory / name
        if dest.exists() and _sha(dest) == meta["sha256"]:
            continue
        urls = [spec["base_url"] + meta["path"]] + ([mirror.rstrip("/") + "/" + name] if mirror else [])
        last: Exception | None = None
        for url in urls:
            try:
                with httpx.stream("GET", url, timeout=120, follow_redirects=True) as r:
                    r.raise_for_status()
                    with dest.open("wb") as f:
                        for chunk in r.iter_bytes():
                            f.write(chunk)
                if _sha(dest) != meta["sha256"]:
                    raise ChecksumMismatch(f"{name} from {url} does not match models.lock.json")
                last = None
                break
            except Exception as e:  # try the mirror next
                last = e
        if last is not None:
            raise last
    return directory


def labels(directory: Path, json_name: str) -> list[str]:
    return json.loads((directory / json_name).read_text())["classes"]
