"""``crate daily``: feedback -> pick families -> gather candidates -> analyse -> select -> Crate Daily playlist.

Runs every morning (user decision 2026-10-13; it was weekly before). File and module names still say "weekly"
(data/weekly.json, weekly.py) so the app and history keep working; the playlist is "Crate Daily".

Selection rules are in ``discover.py``: 20 taste picks from 5 rotating families (equal chance per family, not
proportional to the library) and 10 discovery picks weighted towards families the library has little of.
"""

from __future__ import annotations

import logging
import tempfile
from collections import Counter
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import numpy as np

from . import consensus, discover, taste
from .analyse import Analyzer, auto_tags
from .deezer import Deezer, DeezerTrack
from .lastfm import LastFm
from .listenbrainz import ListenBrainz
from .spotify import Spotify

log = logging.getLogger(__name__)

SEEDS_PER_FAMILY = 6
ANALYSE_PER_TASTE_FAMILY = 40
ANALYSE_PER_DISCOVERY_SLOT = 12
TAGS_PER_DISCOVERY_FAMILY = 3
MIN_CANDIDATES = 30
RECENT_DAYS = 30  # a track is not suggested again for 30 days
NOT_LIKED_WEIGHT = 0.3
FEEDBACK_AFTER_DAYS = 1  # yesterday's mix: not liked after a day counts as a weak "no"


def day_label(now: datetime) -> str:
    """The mix's label: ISO date, e.g. "2026-10-13"."""
    return now.date().isoformat()


def week_label(now: datetime) -> str:
    y, w, _ = now.isocalendar()
    return f"{y}-W{w:02d}"


def collect_feedback(spotify: Spotify, weekly: dict, feedback: dict, now: datetime) -> int:
    """Last week's tracks: liked -> 1.0, not liked after 7 days -> 0 with weight 0.3. Returns items added."""
    if not weekly.get("tracks"):
        return 0
    made = datetime.fromisoformat(weekly["generated_at"].replace("Z", "+00:00"))
    if now - made < timedelta(days=FEEDBACK_AFTER_DAYS):
        return 0
    done = {f["key"] for f in feedback.setdefault("items", []) if f.get("week") == weekly.get("week")}
    tracks = [t for t in weekly["tracks"] if t["key"] not in done and t.get("uri")]
    flags = spotify.contains([t["uri"] for t in tracks])
    for t in tracks:
        liked = flags.get(t["uri"], False)
        feedback["items"].append(
            {
                "key": t["key"],
                "week": weekly.get("week"),
                "liked": liked,
                "weight": 1.0 if liked else NOT_LIKED_WEIGHT,
                "vector": t.get("vector"),
                "family": t.get("family"),
                "kind": t.get("kind"),
            }
        )
    return len(tracks)


def recent_keys(history: list[dict], now: datetime) -> set[str]:
    cutoff = day_label(now - timedelta(days=RECENT_DAYS))
    return {k for h in history if (h.get("day") or h.get("week", "")) >= cutoff for k in h["keys"]}


def feedback_model(feedback: dict) -> np.ndarray | None:
    items = [f for f in feedback.get("items", []) if f.get("vector")]
    if len(items) < discover.FEEDBACK_MIN or len({f["liked"] for f in items}) < 2:
        return None
    x = np.array([f["vector"] for f in items], dtype=float)
    y = np.array([1.0 if f["liked"] else 0.0 for f in items])
    w = np.array([f["weight"] for f in items])
    return taste.logistic(x, y, w)


def _dedupe(pairs: list[tuple[str, str]]) -> list[tuple[str, str]]:
    seen, out = set(), []
    for a, t in pairs:
        k = (a.casefold(), t.casefold())
        if a and t and k not in seen:
            seen.add(k)
            out.append((a, t))
    return out


def gather_taste(
    library: dict, family: str, lastfm: LastFm | None, lb: ListenBrainz | None, deezer: Deezer, rng: np.random.Generator
) -> list[tuple[str, str]]:
    """(artist, title) similar to a sample of the user's own tracks in this family."""
    members = sorted(tid for tid, t in library["tracks"].items() if t.get("family") == family)
    out: list[tuple[str, str]] = []
    for tid in rng.choice(members, min(SEEDS_PER_FAMILY, len(members)), replace=False):
        t = library["tracks"][tid]
        artist, title = (t.get("artists") or [""])[0], t.get("title") or ""
        try:
            if lastfm:
                out += lastfm.similar_tracks(artist, title, limit=20)
                for a in lastfm.similar_artists(artist, limit=4):
                    out += lastfm.top_tracks(a, limit=3)
            if lb:
                out += lb.radio(artist)[:10]
            dz_artist = deezer.search_artist(artist)
            for rel in deezer.related_artists(dz_artist)[:4] if dz_artist else []:
                out += deezer.top_titles(rel["id"], limit=2)
        except Exception as e:  # one source failing never stops the week
            log.info("taste sources failed for %s: %s", artist, e)
    return _dedupe(out)


def gather_discovery(family: str, lastfm: LastFm | None, rng: np.random.Generator) -> list[tuple[str, str]]:
    """(artist, title) from community charts for the family's tags; no seed from the library needed."""
    if not lastfm:
        return []
    tags = sorted(consensus.FAMILIES[family][1])
    out: list[tuple[str, str]] = []
    for tag in rng.choice(tags, min(TAGS_PER_DISCOVERY_FAMILY, len(tags)), replace=False):
        try:
            out += lastfm.tag_top_tracks(str(tag), limit=50, page=int(rng.integers(1, 5)))
        except Exception as e:
            log.info("tag chart %s failed: %s", tag, e)
    return _dedupe(out)


def reason(kind: str, family: str, counts: dict[str, int]) -> str:
    """One-line "why this track?" for the Weekly tab."""
    name = consensus.FAMILY_NAMES.get(family, family)
    n = counts.get(family, 0)
    if kind == "taste":
        return f"Your taste · {name}: sounds like your {n} {name.lower()} tracks"
    have = "none yet" if n == 0 else f"{n} track" + ("s" if n != 1 else "")
    return f"Discovery · {name}: you have {have}, close to your overall sound"


def own_family(feats, requested: str, kind: str) -> str:
    """A discovery pick is labelled by its own sound (strongest audio style), not by the chart it came from,
    so likes are credited to the right family. Taste picks keep their family."""
    if kind != "discovery" or not feats.styles:
        return requested
    strongest = max(feats.styles, key=lambda s: (feats.styles[s], s))
    return consensus.family_of_style(strongest) or requested


def run(
    library: dict,
    vectors: dict[str, np.ndarray],
    weekly: dict,
    feedback: dict,
    spotify: Spotify,
    deezer: Deezer,
    lastfm: LastFm | None,
    lb: ListenBrainz | None,
    analyzer: Analyzer,
    project: Callable[[np.ndarray], np.ndarray],
    now: datetime | None = None,
    seed: int | None = None,
) -> dict[str, Any] | None:
    """Build next week's mix. Returns the new weekly.json, or None to keep last week's (too few candidates)."""
    now = now or datetime.now(UTC)
    rng = np.random.default_rng(seed)
    collect_feedback(spotify, weekly, feedback, now)
    stats = discover.family_stats(feedback)

    tracks = library["tracks"]
    counts = Counter(t["family"] for t in tracks.values() if t.get("family"))
    ids = sorted(vectors)
    profile, _ = taste.profile(np.stack([vectors[i] for i in ids]), seed=int(rng.integers(1 << 31)))
    fam_centroids = {}
    for f in counts:
        vs = [vectors[t] for t in ids if tracks.get(t, {}).get("family") == f]
        if vs:
            fam_centroids[f] = np.mean(vs, axis=0)

    n_discovery = discover.split(feedback)
    n_taste = discover.MIX_SIZE - n_discovery
    taste_fams = discover.taste_families(counts, stats, rng, n=max(1, n_taste // discover.PER_FAMILY))
    slots: dict[tuple[str, str], int] = {}
    for i, f in enumerate(taste_fams):
        slots[("taste", f)] = n_taste // len(taste_fams) + (1 if i < n_taste % len(taste_fams) else 0)
    for f, n in discover.discovery_slots(counts, consensus.FAMILY_ORDER, stats, rng, n_discovery).items():
        slots[("discovery", f)] = n
    log.info("weekly slots: %s", slots)

    liked_isrc = {t.get("isrc") for t in tracks.values() if t.get("isrc")}
    liked_dz = {(t.get("source") or {}).get("deezer_id") for t in tracks.values()}
    recent = recent_keys(feedback.setdefault("history", []), now)

    cands: list[discover.Candidate] = []
    meta: dict[str, dict] = {}
    with tempfile.TemporaryDirectory(prefix="crate-weekly-") as tmp:
        for (kind, fam), n in sorted(slots.items()):
            pairs = gather_taste(library, fam, lastfm, lb, deezer, rng) if kind == "taste" else gather_discovery(fam, lastfm, rng)
            rng.shuffle(pairs)
            budget = ANALYSE_PER_TASTE_FAMILY if kind == "taste" else ANALYSE_PER_DISCOVERY_SLOT * n
            analysed = 0
            for artist, title in pairs:
                if analysed >= budget:
                    break
                try:
                    dz: DeezerTrack | None = deezer.search_track(artist, title)
                    if not dz or not dz.preview or not dz.isrc or dz.isrc in liked_isrc or dz.id in liked_dz:
                        continue
                    key = f"isrc:{dz.isrc}"
                    if key in recent or key in meta:
                        continue
                    path = deezer.http.download(dz.preview, Path(tmp) / "c.mp3")
                    feats = analyzer.analyse(path)
                    path.unlink(missing_ok=True)
                except Exception as e:
                    log.info("candidate %s - %s skipped: %s", artist, title, e)
                    continue
                analysed += 1
                vec = project(feats.embedding)
                cands.append(discover.Candidate(key, artist, vec, own_family(feats, fam, kind), kind))
                meta[key] = {
                    "artists": [artist],
                    "title": title,
                    "duration_ms": dz.duration_s * 1000,
                    "isrc": dz.isrc,
                    "deezer_id": dz.id,
                    "auto": auto_tags(feats, dz.bpm),
                    "vector": [round(float(v), 4) for v in vec],
                }

    if len(cands) < MIN_CANDIDATES:
        log.warning("only %d candidates; keeping last week's mix", len(cands))
        return None

    scores = discover.score(cands, fam_centroids, profile.centroids, feedback_model(feedback))
    picks = discover.select(cands, scores, slots, size=discover.MIX_SIZE * 2)
    out_tracks = []
    for p in picks:
        if len(out_tracks) >= discover.MIX_SIZE:
            break
        m = meta[p.key]
        sp = spotify.search_isrc(m["isrc"])
        if not sp:
            continue
        out_tracks.append(
            {
                **m,
                "key": p.key,
                "id": sp["id"],
                "uri": sp["uri"],
                "title": sp.get("name", m["title"]),
                "cover": ((sp.get("album") or {}).get("images") or [{}])[0].get("url"),
                "score": p.score,
                "family": p.family,
                "kind": p.kind,
                "reason": reason(p.kind, p.family, counts),
            }
        )
    day = day_label(now)
    feedback["history"].append({"day": day, "keys": [t["key"] for t in out_tracks]})
    cutoff = day_label(now - timedelta(days=RECENT_DAYS))
    feedback["history"] = [h for h in feedback["history"] if (h.get("day") or h.get("week", "")) >= cutoff]
    return {
        "schema": 1,
        "week": day,  # kept for older app versions; same as "date"
        "date": day,
        "generated_at": now.replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "candidates": len(cands),
        "slots": {f"{k}:{f}": n for (k, f), n in sorted(slots.items())},
        "tracks": out_tracks,
    }
