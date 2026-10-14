"""``crate analyse``: match pending tracks to Deezer, analyse the 30 s preview, write ``auto`` tags.

Audio never leaves the temp dir and is deleted right after analysis.
"""

from __future__ import annotations

import logging
import tempfile
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

import numpy as np

from . import bpm as bpm_mod
from . import keys, moods, styles
from .deezer import Deezer
from .http import QuotaExceeded
from .library import now_iso

log = logging.getLogger(__name__)


@dataclass
class AudioFeatures:
    """Everything the models say about one preview."""

    styles: dict[str, float]  # 400 Discogs style activations
    embedding: np.ndarray  # 1280-d Discogs-EffNet mean embedding
    mood_outputs: dict[str, dict[str, float]]
    vocal: float
    danceability: float
    arousal: float
    loudness_lufs: float
    onset_rate: float
    tempocnn_bpm: float | None
    rhythm_bpm: float | None
    key_tonic: str
    key_scale: str
    key_strength: float


class Analyzer(Protocol):
    def analyse(self, path: Path) -> AudioFeatures: ...


def auto_tags(f: AudioFeatures, deezer_bpm: float | None, mood_cuts: dict | None = None) -> dict[str, Any]:
    """Pure: model features -> the ``auto`` block of library.json (energy is filled in by energy.recompute)."""
    top = styles.top_styles(f.styles)
    b = bpm_mod.resolve(f.tempocnn_bpm, f.rhythm_bpm, deezer_bpm, f.danceability)
    try:
        key: str | None = keys.to_camelot(f.key_tonic, f.key_scale)
    except ValueError:
        key = None
    out: dict[str, Any] = {
        "genres": styles.genres_from_styles(f.styles),
        "styles": top,
        "key": key,
        "key_conf": round(f.key_strength, 3),
        "moods_raw": (raw_moods := moods.combine(f.mood_outputs)),
        "moods": moods.calibrate(raw_moods, mood_cuts if mood_cuts is not None else moods.cutoffs()),
        "vocal": round(f.vocal, 3),
        "energy_features": {
            "arousal": round(f.arousal, 4),
            "loudness": round(f.loudness_lufs, 2),
            "onset_rate": round(f.onset_rate, 3),
            "danceability": round(f.danceability, 4),
        },
    }
    if b:
        out.update(bpm=b.bpm, bpm_conf=b.confidence, bpm_candidates=b.candidates)
    return out


def deezer_only_tags(deezer_bpm: float | None) -> dict[str, Any] | None:
    """For ``no_preview`` tracks: Deezer's BPM as a low-confidence value, nothing else."""
    if not deezer_bpm:
        return None
    return {"bpm": round(deezer_bpm * 2) / 2, "bpm_conf": 0.2, "bpm_candidates": [round(deezer_bpm * 2) / 2]}


@dataclass
class RunResult:
    tagged: int = 0
    no_preview: int = 0
    errors: int = 0
    stopped_on_quota: bool = False


def analyse_pending(
    library: dict,
    deezer: Deezer,
    analyzer: Analyzer,
    model_version: str,
    on_embedding: Callable[[str, np.ndarray], None],
    max_tracks: int | None = None,
    force: set[str] | None = None,
) -> RunResult:
    """Analyse ``pending`` tracks oldest-liked first, then retry ``error`` ones, plus any IDs in ``force``."""
    tracks = library["tracks"]
    order = {"pending": 0, "error": 1}
    todo = [tid for tid, t in tracks.items() if t.get("status") in order or (force and tid in force)]
    todo.sort(key=lambda tid: (order.get(tracks[tid].get("status"), 0), tracks[tid].get("liked_at") or ""))
    if max_tracks is not None:
        todo = todo[:max_tracks]
    res = RunResult()
    with tempfile.TemporaryDirectory(prefix="crate-") as tmp:
        for tid in todo:
            t = tracks[tid]
            try:
                dz = deezer.match(t.get("isrc"), t.get("title") or "", t.get("artists") or [], t.get("duration_ms"))
                source = {"deezer_id": dz.id if dz else None, "analysed_at": now_iso(), "model_version": model_version}
                if not dz or not dz.preview:
                    auto = deezer_only_tags(dz.bpm if dz else None)
                    t.update(status="no_preview", source=source)
                    if auto:
                        t["auto"] = auto
                    else:
                        t.pop("auto", None)
                    res.no_preview += 1
                    continue
                path = Path(tmp) / f"{tid}.mp3"
                deezer.http.download(dz.preview, path)
                try:
                    feats = analyzer.analyse(path)
                finally:
                    path.unlink(missing_ok=True)
                t["auto"] = auto_tags(feats, dz.bpm)
                t.update(status="tagged", source=source)
                on_embedding(tid, feats.embedding)
                res.tagged += 1
            except QuotaExceeded:
                log.warning("quota exceeded; stopping, next run resumes")
                res.stopped_on_quota = True
                break
            except Exception as e:  # one bad track never stops the run
                log.warning("analyse %s failed: %s", tid, e)
                t["status"] = "error"
                t["error"] = str(e)[:200]
                res.errors += 1
    return res
