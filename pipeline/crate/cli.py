"""``crate`` command line. Every routine task is one command; see docs/runbook.md.

Configuration comes from environment variables (GitHub Actions secrets in CI, a local ``.env`` you source
yourself on the Mac). Tokens are never printed except by ``crate auth``, which exists to show you one.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import subprocess
import sys
import webbrowser
from datetime import UTC, date, datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlsplit

import numpy as np

from . import energy, health, migrate, models, notify, query, report, vectors
from .deezer import Deezer
from .http import Http, QuotaExceeded
from .library import (
    UnsupportedSchema,
    empty_library,
    empty_overrides,
    load_json,
    now_iso,
    save_json,
    upsert_saved,
)
from .spotify import AUTHORIZE_URL, MIX_NAME, NOW_NAME, SCOPES, PlaylistWriter, Spotify, find_or_create

log = logging.getLogger("crate")

REPO = Path(os.environ.get("CRATE_REPO", Path(__file__).resolve().parents[2]))
DATA = REPO / "data"
# Repo-local, git-ignored: HTTP cache, raw embeddings (needed for `crate vectors --fit`), checkpoints.
CACHE = Path(os.environ.get("CRATE_CACHE", REPO / ".cache"))
LOCAL_REDIRECT = "http://127.0.0.1:8888/callback"


def env(name: str, required: bool = True) -> str | None:
    v = os.environ.get(name)
    if required and not v:
        sys.exit(f"Missing environment variable {name}. See docs/runbook.md#secrets")
    return v


def http(cache: bool = True) -> Http:
    return Http(cache_dir=CACHE / "http" if cache else None)


def spotify_client(h: Http) -> Spotify:
    return Spotify(h, env("SPOTIFY_CLIENT_ID"), env("SPOTIFY_CLIENT_SECRET"), env("SPOTIFY_REFRESH_TOKEN"))


def persist_rotated_token(sp: Spotify) -> None:
    """Spotify may hand out a new refresh token; store it as the Actions secret (needs GH_SECRETS_PAT)."""
    if not sp.rotated_refresh_token:
        return
    pat, repo = os.environ.get("GH_SECRETS_PAT"), os.environ.get("GITHUB_REPOSITORY")
    if not (pat and repo):
        log.warning(
            "Spotify rotated the refresh token but GH_SECRETS_PAT/GITHUB_REPOSITORY are unset; "
            "run `crate auth` again (docs/runbook.md#spotify-token)"
        )
        return
    subprocess.run(
        ["gh", "secret", "set", "SPOTIFY_REFRESH_TOKEN", "--repo", repo, "--body", sp.rotated_refresh_token],
        check=True,
        env={**os.environ, "GH_TOKEN": pat},
        capture_output=True,
    )
    log.info("refresh token rotated and saved")


def load_all() -> tuple[dict, dict, dict]:
    return (
        load_json(DATA / "library.json", empty_library()),
        load_json(DATA / "overrides.json", empty_overrides()),
        load_json(DATA / "state.json", {"schema": 1}),
    )


# --- commands --------------------------------------------------------------------------------------------


def cmd_auth(args: argparse.Namespace) -> None:
    """One-time: log in with Spotify in the browser and print the refresh token to store as a secret."""
    cid = env("SPOTIFY_CLIENT_ID")
    got: dict[str, str] = {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            q = parse_qs(urlsplit(self.path).query)
            got.update({k: v[0] for k, v in q.items()})
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"Crate: you can close this tab.")

        def log_message(self, *a):
            pass

    url = (
        AUTHORIZE_URL
        + "?"
        + urlencode({"client_id": cid, "response_type": "code", "redirect_uri": LOCAL_REDIRECT, "scope": SCOPES})
    )
    print("Opening Spotify login…\n" + url)
    webbrowser.open(url)
    server = HTTPServer(("127.0.0.1", 8888), Handler)
    while "code" not in got and "error" not in got:
        server.handle_request()
    if "error" in got:
        sys.exit(f"Spotify said: {got['error']}")
    sp = Spotify(http(False), cid, env("SPOTIFY_CLIENT_SECRET"), "")
    tokens = sp.exchange_code(got["code"], LOCAL_REDIRECT)
    if args.save:
        save_env(REPO / ".env", "SPOTIFY_REFRESH_TOKEN", tokens["refresh_token"])
        print("\nRefresh token saved to .env as SPOTIFY_REFRESH_TOKEN (not printed).")
        return
    print("\nRefresh token (store it as the SPOTIFY_REFRESH_TOKEN secret, see docs/runbook.md#spotify-token):\n")
    print(tokens["refresh_token"])


def save_env(path: Path, key: str, value: str) -> None:
    """Set ``key=value`` in a dotenv file, replacing an existing line or appending one. Keeps it private (0600)."""
    lines = path.read_text().splitlines() if path.exists() else []
    out = [line for line in lines if not line.startswith(f"{key}=")]
    idx = next((i for i, line in enumerate(lines) if line.startswith(f"{key}=")), len(out))
    out.insert(min(idx, len(out)), f"{key}={value}")
    path.write_text("\n".join(out) + "\n")
    path.chmod(0o600)


def cmd_sync(args: argparse.Namespace) -> None:
    lib, _, state = load_all()
    sp = spotify_client(http(False))
    try:
        saved = list(sp.saved_tracks())
    finally:
        persist_rotated_token(sp)
    counts = upsert_saved(lib, saved)
    lib["generated_at"] = now_iso()
    state["last_sync"] = now_iso()
    save_json(DATA / "library.json", lib)
    save_json(DATA / "state.json", state)
    print(f"sync: {counts['added']} new, {counts['removed']} removed, {counts['total']} total")


def _embedding_sink(store: vectors.VectorStore):
    pca = vectors.load_pca(DATA / "pca.npz")
    raw_dir = CACHE / "embeddings"
    raw_dir.mkdir(parents=True, exist_ok=True)

    def sink(tid: str, emb: np.ndarray) -> None:
        np.save(raw_dir / f"{tid}.npy", emb.astype(np.float32))
        if pca is not None:
            store.rows[tid] = vectors.project(emb, *pca)

    return sink


def cmd_analyse(args: argparse.Namespace) -> None:
    from .analyse import analyse_pending
    from .essentia_analyzer import EssentiaAnalyzer

    lib, _, _ = load_all()
    force = set(lib["tracks"]) if args.force_all else set(args.force or [])
    store = vectors.VectorStore.load(DATA)
    res = analyse_pending(
        lib, Deezer(http()), EssentiaAnalyzer(), models.model_version(), _embedding_sink(store), max_tracks=args.max, force=force
    )
    changed = energy.recompute(lib)
    from . import moods

    moods.recalibrate(lib)
    lib["generated_at"] = now_iso()
    save_json(DATA / "library.json", lib)
    store.save(DATA, keep=set(lib["tracks"]))
    print(
        f"analyse: {res.tagged} tagged, {res.no_preview} no preview, {res.errors} errors, "
        f"energy changed for {changed}{', stopped on quota' if res.stopped_on_quota else ''}"
    )


def cmd_moods(args: argparse.Namespace) -> None:
    """Re-apply mood_cutoffs.json to every track and show examples around each cut-off for listening."""
    from . import moods

    lib, _, _ = load_all()
    for t in lib["tracks"].values():  # one-time: data analysed before per-mood cut-offs kept raw scores in auto.moods
        auto = t.get("auto") or {}
        if "moods" in auto and "moods_raw" not in auto:
            auto["moods_raw"] = auto["moods"]
    changed = moods.recalibrate(lib)
    save_json(DATA / "library.json", lib)
    cuts = moods.cutoffs()
    tagged = [(tid, t) for tid, t in lib["tracks"].items() if (t.get("auto") or {}).get("moods_raw")]
    print(f"moods: recalibrated {changed} tracks\n")
    for mood, c in cuts.items():
        if c is None:
            print(f"{mood:12} (not tagged automatically)")
            continue
        scored = sorted(((t["auto"]["moods_raw"].get(mood, 0.0), tid) for tid, t in tagged), reverse=True)
        n = sum(1 for v, _ in scored if v >= c)
        print(f"{mood:12} cut-off {c:<5} -> {n:4d} tracks ({n / max(1, len(scored)):.0%})")
        if args.examples:
            above = [x for x in scored if x[0] >= c][-args.examples :]
            below = [x for x in scored if x[0] < c][: args.examples]
            for label, rows in (("just in ", above), ("just out", below)):
                for v, tid in rows:
                    t = lib["tracks"][tid]
                    print(
                        f"    {label} {v:.3f}  {t.get('title')} — {', '.join(t.get('artists') or [])}  "
                        f"https://open.spotify.com/track/{tid}"
                    )


STARTER_MOODS = (
    "dark",
    "energetic",
    "dreamy",
    "uplifting",
    "relaxing",
    "party",
    "aggressive",
    "sad",
    "happy",
    "meditative",
    "romantic",
)
STARTER_SHARE = 0.15
# Hypnotic defined by the Last.fm community's scene tags rather than by hand (user decision 2026-10-12).
COMMUNITY_HYPNOTIC = ("dub techno", "deep techno", "hypnotic techno")


def _vibe_context(lib: dict) -> tuple[dict, dict[str, set[str]]]:
    from . import vibes

    vf = load_json(DATA / "vibes.json", {"schema": 1, "vibes": {}})
    store = vectors.VectorStore.load(DATA)
    from . import tags

    artists = {tid: t.get("artists") or [] for tid, t in lib["tracks"].items()}
    tt = {tid: tags.track_tags(t) for tid, t in lib["tracks"].items()}
    overrides = load_json(DATA / "overrides.json", {}).get("tracks", {})
    energy = vibes.energies(lib["tracks"], overrides)
    return vf, vibes.all_members(vf.get("vibes", {}), artists, store.rows, tt, energy)


def cmd_vibes(args: argparse.Namespace) -> None:
    """List vibes with their members; ``--seed`` creates the starter set once (the app owns the file after)."""
    from . import vibes

    lib, _, _ = load_all()
    path = DATA / "vibes.json"
    if args.seed:
        if path.exists() and not args.force:
            sys.exit("data/vibes.json exists (the app owns it); use --force to overwrite")
        tagged = {tid: t for tid, t in lib["tracks"].items() if (t.get("auto") or {}).get("moods_raw")}
        out = {
            "hypnotic": {
                "name": "Hypnotic",
                "seeds": [],
                "artists": [],
                "seed_tags": list(COMMUNITY_HYPNOTIC),
                "exclude": [],
                "share": STARTER_SHARE,
            },
            "melancholic": {"name": "Melancholic", "seeds": [], "artists": [], "exclude": [], "share": STARTER_SHARE},
        }
        for mood in STARTER_MOODS:
            top = sorted(tagged, key=lambda tid: -tagged[tid]["auto"]["moods_raw"].get(mood, 0.0))[:5]
            out[mood] = {"name": mood.capitalize(), "seeds": top, "artists": [], "exclude": [], "share": STARTER_SHARE}
        save_json(path, {"schema": 1, "vibes": out})
        print(f"wrote {len(out)} starter vibes to data/vibes.json (melancholic has no seeds yet)")
    from . import tags

    vf, members = _vibe_context(lib)
    store = vectors.VectorStore.load(DATA)
    artists = {tid: t.get("artists") or [] for tid, t in lib["tracks"].items()}
    tt = {tid: tags.track_tags(t) for tid, t in lib["tracks"].items()}
    for vid, v in vf.get("vibes", {}).items():
        seeds = vibes.seed_tracks(v, artists, store.rows, tt)
        print(f"\n{v['name']} [{vid}]: {len(members[vid])} tracks, {len(seeds)} seed tracks")
        top = [tid for tid, _ in vibes.ranked(v, artists, store.rows, tt) if tid not in seeds][: args.top]
        for tid in top:
            t = lib["tracks"][tid]
            print(f"    {t.get('title')} — {', '.join(t.get('artists') or [])}")


def cmd_tags(args: argparse.Namespace) -> None:
    """Fetch Last.fm artist tags (missing or older than 30 days) and merge all tag sources into library.json."""
    from datetime import timedelta

    from . import tags
    from .lastfm import LastFm

    lib, _, _ = load_all()
    at = load_json(DATA / "artist_tags.json", {"schema": 1, "artists": {}})
    key = os.environ.get("LASTFM_API_KEY")
    if key and not args.offline:
        lf = LastFm(Http(cache_dir=None), key)
        cutoff = (datetime.now(UTC) - timedelta(days=tags.REFRESH_DAYS)).isoformat()
        names = sorted({a for t in lib["tracks"].values() for a in t.get("artists") or []})
        todo = [a for a in names if a not in at["artists"] or at["artists"][a].get("fetched_at", "") < cutoff]
        for a in todo[: args.max]:
            try:
                at["artists"][a] = {"lastfm": tags.lastfm_artist_tags(lf.artist_tags(a)), "fetched_at": now_iso()}
            except QuotaExceeded:
                break
            except Exception as e:  # one artist failing never stops the run
                log.info("last.fm tags for %s failed: %s", a, e)
        save_json(DATA / "artist_tags.json", at)
        print(f"tags: fetched {min(len(todo), args.max)} artists from Last.fm")
    bp = load_json(DATA / "beatport.json", {"schema": 1, "tracks": {}, "artists": {}})
    dc = load_json(DATA / "discogs.json", {"schema": 1, "tracks": {}})
    changed = tags.merge(lib, at, bp, dc)
    save_json(DATA / "library.json", lib)
    with_tags = sum(1 for t in lib["tracks"].values() if t.get("tags"))
    print(f"tags: {with_tags} of {len(lib['tracks'])} tracks have tags ({changed} changed)")


def cmd_discogs(args: argparse.Namespace) -> None:
    """Look up each track on Discogs (genres/styles) into data/discogs.json; misses are retried after 90 days."""
    from datetime import timedelta

    from .discogs import REFRESH_DAYS, Discogs

    lib, _, _ = load_all()
    dc = load_json(DATA / "discogs.json", {"schema": 1, "tracks": {}})
    client = Discogs(Http(), env("DISCOGS_TOKEN"))
    cutoff = (datetime.now(UTC) - timedelta(days=REFRESH_DAYS)).isoformat()
    todo = [
        tid
        for tid, t in lib["tracks"].items()
        if tid not in dc["tracks"] or (not dc["tracks"][tid].get("found") and dc["tracks"][tid].get("checked_at", "") < cutoff)
    ]
    found = 0
    for n, tid in enumerate(todo[: args.max], 1):
        t = lib["tracks"][tid]
        try:
            hit = client.lookup((t.get("artists") or [""])[0], t.get("title") or "")
        except QuotaExceeded:
            break
        except Exception as e:
            log.info("discogs %s failed: %s", tid, e)
            continue
        dc["tracks"][tid] = {"found": bool(hit), "checked_at": now_iso(), **(hit or {})}
        found += bool(hit)
        if n % 50 == 0:
            save_json(DATA / "discogs.json", dc)
            print(f"discogs: {n}/{min(len(todo), args.max)}", flush=True)
    save_json(DATA / "discogs.json", dc)
    total = sum(1 for v in dc["tracks"].values() if v.get("found"))
    print(f"discogs: {found} new matches this run; {total} of {len(lib['tracks'])} tracks matched overall")


def cmd_consensus(args: argparse.Namespace) -> None:
    """Score how far audio, Discogs, Last.fm and Beatport agree on each track's style family."""
    from . import consensus

    lib, ov, _ = load_all()
    at = load_json(DATA / "artist_tags.json", {"artists": {}})
    dc = load_json(DATA / "discogs.json", {"tracks": {}})
    bp = load_json(DATA / "beatport.json", {"tracks": {}})
    hist = consensus.apply(lib, ov, at, dc, bp)
    moved = consensus.assign_families(lib, ov)
    save_json(DATA / "library.json", lib)
    tax = load_json(DATA / "taxonomy.json", {"schema": 1, "genres": {}, "aliases": {}})
    if tax.get("families") != consensus.FAMILY_ORDER or tax.get("family_names") != consensus.FAMILY_NAMES:
        tax["families"] = consensus.FAMILY_ORDER
        tax["family_names"] = consensus.FAMILY_NAMES
        save_json(DATA / "taxonomy.json", tax)
    print(f"family changed for {moved} tracks")
    print("consensus (votes for the winning family / sources with an opinion):")
    for k, n in sorted(hist.items(), key=lambda kv: (-int(kv[0].split("/")[1]), -int(kv[0].split("/")[0]))):
        print(f"  {k}: {n} tracks")


def cmd_energy(args: argparse.Namespace) -> None:
    lib, _, _ = load_all()
    print(f"energy changed for {energy.recompute(lib)} tracks")
    save_json(DATA / "library.json", lib)


def cmd_vectors(args: argparse.Namespace) -> None:
    """Export vectors. ``--fit`` (re)fits the PCA basis from cached raw embeddings and re-projects everything."""
    lib, _, _ = load_all()
    raw_dir = CACHE / "embeddings"
    ids = sorted(tid for tid in lib["tracks"] if (raw_dir / f"{tid}.npy").exists())
    if args.fit:
        if len(ids) < vectors.DIMS * 4:
            sys.exit(f"only {len(ids)} cached embeddings; analyse more tracks first")
        mean, comps = vectors.fit_pca(np.stack([np.load(raw_dir / f"{i}.npy") for i in ids]))
        vectors.save_pca(DATA / "pca.npz", mean, comps)
    pca = vectors.load_pca(DATA / "pca.npz")
    if pca is None:
        sys.exit("no data/pca.npz yet; run `crate vectors --fit` after the backfill")
    store = vectors.VectorStore.load(DATA)
    for i in ids:
        if args.fit or i not in store.rows:
            store.rows[i] = vectors.project(np.load(raw_dir / f"{i}.npy"), *pca)
    changed = store.save(DATA, keep=set(lib["tracks"]))
    print(f"vectors: {len(store.rows)} rows{' (changed)' if changed else ''}")


def _query_from_args(args: argparse.Namespace) -> query.Query:
    if args.preset:
        presets = load_json(DATA / "presets.json", {"presets": []})
        items = presets.get("presets", presets) if isinstance(presets, dict) else presets
        match = [p for p in items if p["name"].casefold() == args.preset.casefold()]
        if not match:
            sys.exit(f"no preset named {args.preset!r}")
        return query.Query.from_json(match[0]["query"])
    return query.Query.from_json(json.loads(args.json or "{}"))


def cmd_query(args: argparse.Namespace) -> None:
    lib, ov, state = load_all()
    q = _query_from_args(args)
    members = _vibe_context(lib)[1] if q.vibes_include else None
    picked = query.run(q, lib["tracks"], ov.get("tracks", {}), vibe_members=members)
    total = len(query.matching_ids(q, lib["tracks"], ov.get("tracks", {}), members))
    print(f"query: {total} matches, sending {len(picked)}")
    if args.dry_run:
        for tid in picked[:20]:
            t = lib["tracks"][tid]
            print(f"  {t.get('title')} — {', '.join(t.get('artists') or [])}")
        return
    sp = spotify_client(http(False))
    me = sp.me()["id"]
    presets = load_json(DATA / "presets.json", {})
    known = state.get("now_playlist_id") or (presets.get("now_playlist_id") if isinstance(presets, dict) else None)
    now_id = find_or_create(sp, NOW_NAME, known, me)
    state["now_playlist_id"] = now_id
    PlaylistWriter(sp, {now_id}).replace(now_id, [lib["tracks"][t]["uri"] for t in picked])
    save_json(DATA / "state.json", state)
    persist_rotated_token(sp)
    print(f"Now updated: spotify:playlist:{now_id}")


def cmd_weekly(args: argparse.Namespace) -> None:
    from . import weekly
    from .essentia_analyzer import EssentiaAnalyzer
    from .lastfm import LastFm
    from .listenbrainz import ListenBrainz

    lib, _, state = load_all()
    store = vectors.VectorStore.load(DATA)
    pca = vectors.load_pca(DATA / "pca.npz")
    if pca is None or not store.rows:
        sys.exit("no vectors yet; finish the backfill and run `crate vectors --fit`")
    h = http()
    sp = spotify_client(Http())
    fb = load_json(DATA / "feedback.json", {"schema": 1, "items": [], "history": []})
    prev = load_json(DATA / "weekly.json", {"schema": 1, "tracks": []})
    key = env("LASTFM_API_KEY", required=False)
    new = weekly.run(
        lib,
        store.rows,
        prev,
        fb,
        sp,
        Deezer(h),
        LastFm(h, key) if key else None,
        ListenBrainz(h, os.environ.get("LISTENBRAINZ_TOKEN")),
        EssentiaAnalyzer(),
        lambda e: vectors.project(e, *pca),
    )
    if args.dry_run:
        if new is None:
            print("daily (dry run): too few candidates")
            return
        print(f"daily (dry run): {len(new['tracks'])} tracks from {new['candidates']} candidates; slots {new['slots']}")
        for t in new["tracks"]:
            print(f"  [{t['kind']:>9} · {t['family']:<17}] {t['title'][:40]} — {', '.join(t['artists'])[:30]}")
        save_json(CACHE / "weekly-dry-run.json", new)
        return
    save_json(DATA / "feedback.json", fb)
    if new is None:
        notify.alert("Crate Daily skipped: too few new candidates today (yesterday's mix stays)", "weekly-skipped")
        return
    me = sp.me()["id"]
    wid = find_or_create(sp, MIX_NAME, state.get("weekly_playlist_id"), me)
    if not state.get("mix_renamed"):  # one-time: the old "Weekly Mix" playlist keeps its ID and privacy
        sp.rename_playlist(wid, MIX_NAME)
        state["mix_renamed"] = True
    state["weekly_playlist_id"] = wid
    PlaylistWriter(sp, {wid}).replace(wid, [t["uri"] for t in new["tracks"]])
    new["playlist_id"] = wid
    save_json(DATA / "weekly.json", new)
    save_json(DATA / "state.json", state)
    persist_rotated_token(sp)
    # Silent by design (user decision 2026-10-13): ntfy is for problems only.
    print(f"daily: {len(new['tracks'])} tracks from {new['candidates']} candidates")


def cmd_migrate(args: argparse.Namespace) -> None:
    _, _, state = load_all()
    sp = spotify_client(http(False))
    me = sp.me()["id"]
    stamp = date.today().strftime("%Y%m%d")
    bpath = REPO / "backup" / f"playlists-{stamp}.json"
    if bpath.exists():
        data = json.loads(bpath.read_text())
    else:
        data = migrate.backup(sp, me)
        save_json(bpath, data)
        print(f"backup written: {bpath.relative_to(REPO)} (commit it before --execute)")
    uris = sorted({it["uri"] for p in data["playlists"] for it in p.get("items") or [] if it.get("uri")})
    liked = {u for u, ok in sp.contains([u for u in uris if u.startswith("spotify:track:")]).items() if ok}
    crate_ids = {state.get("now_playlist_id"), state.get("weekly_playlist_id")} - {None}
    p = migrate.plan(data, liked, crate_ids)
    print(p.summary())
    if args.execute:
        migrate.execute(sp, p, args.confirm or "", migrate.Checkpoint.load(CACHE / f"migrate-{p.code}.json"))
        print("Done. Newly liked tracks are picked up as pending by the next `crate sync`.\n" + migrate.RECOVER_NOTE)
    persist_rotated_token(sp)


def cmd_report(args: argparse.Namespace) -> None:
    lib, ov, _ = load_all()
    gt_path = REPO / "spec" / "ground_truth.json"
    gt = json.loads(gt_path.read_text()) if gt_path.exists() else None
    md = report.render(lib, ov, ground_truth=gt)
    backups = sorted((REPO / "backup").glob("playlists-*.json"))
    if backups:
        from . import answerkey

        md += "\n" + answerkey.render(answerkey.grade(lib, ov, json.loads(backups[-1].read_text())), lib["tracks"])
    out = REPO / "docs" / "tag-quality-report.md"
    out.write_text(md)
    print(f"wrote {out.relative_to(REPO)}")


def cmd_health(args: argparse.Namespace) -> None:
    lib, _, state = load_all()
    h = Http()
    checks = [health.last_sync(state, datetime.now(UTC)), health.coverage(lib)]
    if os.environ.get("SPOTIFY_REFRESH_TOKEN"):
        sp = spotify_client(h)
        checks.append(health.reachable("Spotify", sp.me, "spotify-token"))
    checks.append(health.reachable("Deezer", lambda: h.get("https://api.deezer.com/track/3135556"), "deezer-previews"))
    if os.environ.get("LASTFM_API_KEY"):
        from .lastfm import LastFm

        checks.append(
            health.reachable("Last.fm", lambda: LastFm(h, os.environ["LASTFM_API_KEY"]).similar_artists("Burial", 1), "lastfm")
        )
    today = date.today()
    creds = [
        ("GitHub token (Actions)", os.environ.get("GH_SECRETS_PAT_EXPIRES")),
        ("GitHub token (app)", os.environ.get("APP_PAT_EXPIRES")),
    ]
    checks += [health.expiry(n, d, today, "github-token") for n, d in creds]
    rep = health.report(checks, state, lib, [health.credential(n, d, today) for n, d in creds])
    save_json(DATA / "health.json", rep)
    for c in checks:
        print(f"{'OK  ' if c.ok and not c.warn else 'WARN' if c.ok else 'FAIL'} {c.name}: {c.message}")
        if not c.ok or c.warn:
            notify.alert(c.message, c.runbook)
    if rep["status"] == "broken":
        sys.exit(1)


def cmd_contract(args: argparse.Namespace) -> None:
    from . import contract

    h = Http()
    api = "https://api.deezer.com"
    probes = {
        "deezer GET /track/isrc": lambda: h.get(f"{api}/track/isrc:GBDUW0000059"),
        "deezer GET /search": lambda: h.get(f"{api}/search", params={"q": "daft punk around the world", "limit": 3}),
        "deezer GET /artist/related": lambda: h.get(f"{api}/artist/27/related", params={"limit": 3}),
    }
    if os.environ.get("SPOTIFY_REFRESH_TOKEN"):
        sp = spotify_client(h)
        probes |= {
            "spotify GET /me": sp.me,
            "spotify GET /me/tracks": lambda: sp.call("GET", "/me/tracks", params={"limit": 2}),
            "spotify GET /me/playlists": lambda: sp.call("GET", "/me/playlists", params={"limit": 2}),
            "spotify GET /search isrc": lambda: sp.call(
                "GET", "/search", params={"q": "isrc:GBDUW0000059", "type": "track", "limit": 1}
            ),
        }
    if os.environ.get("LASTFM_API_KEY"):
        from .lastfm import API as LASTFM

        probes["lastfm track.getsimilar"] = lambda: h.get(
            LASTFM,
            params={
                "method": "track.getsimilar",
                "artist": "Daft Punk",
                "track": "Around the World",
                "api_key": os.environ["LASTFM_API_KEY"],
                "format": "json",
                "limit": 2,
            },
        )
    problems = contract.run(probes)
    for p in problems:
        print("FAIL", p)
        notify.alert(p, "api-changed")
    print(f"contract: {len(probes)} endpoints, {len(problems)} problems")
    if problems:
        sys.exit(1)


def cmd_taxonomy(args: argparse.Namespace) -> None:
    from . import styles

    d = models.ensure()
    old = load_json(DATA / "taxonomy.json", {"aliases": {}})
    tax = styles.taxonomy(models.labels(d, "genre_discogs400-discogs-effnet-1.json"), old.get("aliases"))
    save_json(DATA / "taxonomy.json", tax)
    print(f"taxonomy: {len(tax['genres'])} genres")


def main(argv: list[str] | None = None) -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    ap = argparse.ArgumentParser(prog="crate", description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    au = sub.add_parser("auth", help="one-time Spotify login, prints the refresh token")
    au.add_argument("--save", action="store_true", help="write the token into .env instead of printing it")
    au.set_defaults(fn=cmd_auth)
    sub.add_parser("sync", help="Liked Songs -> data/library.json").set_defaults(fn=cmd_sync)
    a = sub.add_parser("analyse", help="tag pending tracks from Deezer previews")
    a.add_argument("--max", type=int)
    a.add_argument("--force", nargs="*", help="re-analyse these track IDs")
    a.add_argument("--force-all", action="store_true", help="re-analyse every track")
    a.set_defaults(fn=cmd_analyse)
    sub.add_parser("energy", help="recompute energy deciles").set_defaults(fn=cmd_energy)
    sub.add_parser("consensus", help="agreement between audio, Discogs, Last.fm and Beatport per track").set_defaults(
        fn=cmd_consensus
    )
    dg = sub.add_parser("discogs", help="look up Discogs genres/styles for tracks (needs DISCOGS_TOKEN)")
    dg.add_argument("--max", type=int, default=2000)
    dg.set_defaults(fn=cmd_discogs)
    tg = sub.add_parser("tags", help="fetch Last.fm artist tags and merge tag sources into library.json")
    tg.add_argument("--max", type=int, default=2000, help="max artists to fetch this run")
    tg.add_argument("--offline", action="store_true", help="only merge, don't fetch")
    tg.set_defaults(fn=cmd_tags)
    vb = sub.add_parser("vibes", help="list vibes and their closest tracks; --seed creates the starter set")
    vb.add_argument("--seed", action="store_true")
    vb.add_argument("--force", action="store_true")
    vb.add_argument("--top", type=int, default=8, help="closest non-seed tracks to show per vibe")
    vb.set_defaults(fn=cmd_vibes)
    mo = sub.add_parser("moods", help="re-apply mood_cutoffs.json and list examples around each cut-off")
    mo.add_argument("--examples", type=int, default=3, help="tracks to show just above and below each cut-off")
    mo.set_defaults(fn=cmd_moods)
    v = sub.add_parser("vectors", help="export similarity vectors")
    v.add_argument("--fit", action="store_true", help="re-fit the PCA basis (changes every vector)")
    v.set_defaults(fn=cmd_vectors)
    q = sub.add_parser("query", help="run a tag query and overwrite Now")
    q.add_argument("--json", help='query JSON, e.g. \'{"genres_include":["Electronic"]}\'')
    q.add_argument("--preset", help="name of a preset in data/presets.json")
    q.add_argument("--dry-run", action="store_true")
    q.set_defaults(fn=cmd_query)
    wk = sub.add_parser("daily", aliases=["weekly"], help="build today's Crate Daily mix")
    wk.add_argument("--dry-run", action="store_true", help="pick and print, don't write Spotify or data/")
    wk.add_argument("--seed", type=int, help="random seed (reproducible picks)")
    wk.set_defaults(fn=cmd_weekly)
    m = sub.add_parser("migrate", help="one-time playlist migration (dry run unless --execute)")
    m.add_argument("--execute", action="store_true")
    m.add_argument("--confirm", help="code printed by the dry run")
    m.set_defaults(fn=cmd_migrate)
    sub.add_parser("report", help="write docs/tag-quality-report.md").set_defaults(fn=cmd_report)
    sub.add_parser("health", help="check everything, write data/health.json, alert").set_defaults(fn=cmd_health)
    sub.add_parser("contract", help="check upstream APIs still return the fields Crate reads").set_defaults(fn=cmd_contract)
    sub.add_parser("taxonomy", help="regenerate data/taxonomy.json from the model labels").set_defaults(fn=cmd_taxonomy)
    args = ap.parse_args(argv)
    try:
        args.fn(args)
    except QuotaExceeded:
        print("Spotify quota exceeded; stopping cleanly. The next scheduled run resumes.")
    except UnsupportedSchema as e:
        sys.exit(str(e))


if __name__ == "__main__":
    main()
