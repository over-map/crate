# Crate runbook

Copy-paste procedures for keeping Crate running. Every alert from Crate links to one of the sections below.
You need a terminal, `git`, [`uv`](https://docs.astral.sh/uv/) and the [GitHub CLI](https://cli.github.com/) (`gh auth login` once).

Local commands read secrets from your shell. Keep them in `.env` (git-ignored, see `.env.example`) and load them with:

```sh
set -a; source .env; set +a
```

---

## Secrets

Where each secret lives:

| Secret | Where | Used by |
|---|---|---|
| `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET` | GitHub → Settings → Secrets → Actions, and your `.env` | pipeline |
| `SPOTIFY_REFRESH_TOKEN` | Actions secret, `.env` | pipeline |
| `LASTFM_API_KEY` | Actions secret, `.env` | Crate Daily |
| `NTFY_TOPIC` | Actions secret, `.env`, the ntfy app on your phone | alerts |
| `GH_SECRETS_PAT` | Actions secret | rotating `SPOTIFY_REFRESH_TOKEN` automatically |
| `LISTENBRAINZ_TOKEN` (optional) | Actions secret | Crate Daily |
| App GitHub token | iPhone Keychain (entered in Crate's onboarding) | app reads/writes `data/` |
| Spotify login for the app | iPhone Keychain | app sends to Now |

Repository **variables** (Settings → Secrets and variables → Variables): `GH_SECRETS_PAT_EXPIRES` and `APP_PAT_EXPIRES` (`YYYY-MM-DD`). The health check warns 14 days before each date.

## First-time setup (M0)

1. Create the private repo and push: `gh repo create crate --private --source . --push`.
2. At <https://developer.spotify.com/dashboard>, create an app (Web API). Add the redirect URIs `http://127.0.0.1:8888/callback` and `crate://spotify-callback`. Copy the client ID and secret into `.env`.
3. `cd pipeline && uv sync --extra audio`
4. `uv run crate auth`. Log in when the browser opens, then copy the printed refresh token into `.env`.
5. Store the secrets in GitHub:
   ```sh
   for s in SPOTIFY_CLIENT_ID SPOTIFY_CLIENT_SECRET SPOTIFY_REFRESH_TOKEN LASTFM_API_KEY NTFY_TOPIC; do gh secret set -R over-map/crate-data $s --body "${!s}"; done
   ```
6. Create `GH_SECRETS_PAT`: a fine-grained token for **only** the `crate` repo with *Secrets: read and write*. Then run `gh secret set -R over-map/crate-data GH_SECRETS_PAT` and `gh variable set -R over-map/crate-data GH_SECRETS_PAT_EXPIRES --body YYYY-MM-DD`.
7. Get a Last.fm API key at <https://www.last.fm/api/account/create>.
8. Pick a long, random ntfy topic name and subscribe to it in the ntfy app.
9. Check: `uv run crate sync` should print `sync: N new …`.

## Backfill

The first full analysis runs fastest on the Mac. Essentia has macOS arm64 wheels, and the run takes about 2 s per track, so roughly 2 hours for 3,000 tracks.

```sh
cd pipeline
uv sync --extra audio
uv run crate sync
uv run crate analyse            # no cap; safe to interrupt and re-run
uv run crate vectors --fit      # fixes the similarity space once
uv run crate report             # docs/tag-quality-report.md
cd .. && git add data docs && git commit -m "backfill" && git push
```

Alternative: do nothing, and the nightly workflow analyses 400 tracks a night.

## Re-run or force analysis

- One track: `uv run crate analyse --force <spotify_track_id>`
- Everything (e.g. after a model upgrade): `uv run crate analyse --force-all && uv run crate vectors --fit`
- Tracks with `status: error` are retried automatically every night.

## nightly-failed

The alert says "Nightly sync failed" or "Last successful nightly sync was N h ago".

1. Open GitHub → Actions → nightly → the red run. Read the failing step.
2. If the failing step is **Sync**, it's usually Spotify: see [spotify-token](#spotify-token).
3. If it's **Analyse**, a model download may have failed. Re-run the workflow. If it fails again, see [models](#models).
4. If it's **Commit**, a push conflict with the app. Re-run; it rebases automatically.
5. Re-run with **Run workflow** (you can lower `max`).

Quota limits (HTTP 429 `QUOTA_EXCEEDED`) stop the run cleanly without failing it, and the next night carries on.

## spotify-token

The alert says "Spotify login rejected" or "Spotify call failed: HTTP 400/401".

1. Load `.env`, then `cd pipeline && uv run crate auth`.
2. `gh secret set -R over-map/crate-data SPOTIFY_REFRESH_TOKEN --body "<new token>"`. Update `.env` as well.
3. GitHub → Actions → nightly → **Run workflow**.

If the error says the client is invalid, the Spotify app may have been changed or deleted. Check the dashboard, and if the secret was reset, update `SPOTIFY_CLIENT_SECRET`.

The pipeline saves rotated refresh tokens by itself (via `GH_SECRETS_PAT`). If that token has expired, see [github-token](#github-token).

## github-token

Crate uses two fine-grained GitHub tokens. Both expire, at most a year after creation.

**Actions token (`GH_SECRETS_PAT`):**
1. github.com → Settings → Developer settings → Fine-grained tokens → **Generate new token**. Repository access: only `crate`. Permissions: *Secrets: read and write*.
2. `gh secret set -R over-map/crate-data GH_SECRETS_PAT` (paste it).
3. `gh variable set -R over-map/crate-data GH_SECRETS_PAT_EXPIRES --body YYYY-MM-DD`

**App token:**
1. Generate a new fine-grained token: only the `crate` repo, *Contents: read and write*.
2. In Crate on the iPhone: Library → Settings → GitHub token → paste it.
3. `gh variable set -R over-map/crate-data APP_PAT_EXPIRES --body YYYY-MM-DD`

## Re-sign the iPhone app

A free Apple ID signs builds for 7 days. Refresh from Xcode: plug the iPhone into the Mac, open `ios/Crate.xcodeproj`, choose your iPhone at the top, and press ⌘R (Product → Run). Crate shows the days left on the Library tab and, once reminders are on, sends a notification the day before and on the expiry day.

If Crate has already expired it won't open; the same ⌘R brings it back with all data intact. After an **iOS update**, update Xcode if it asks for a newer device support package. Free-account limits are 3 sideloaded apps and 10 App IDs per rolling 7 days. Never change Crate's bundle IDs.

Everything else (the pipeline, Now from `crate query`, Crate Daily) keeps working while the app is expired.

## weekly-failed

1. Actions → daily → the red run. Crate Daily needs `data/pca.npz` and `data/vectors.bin`. If they're missing, finish the [backfill](#backfill).
2. Re-run with **Run workflow**.

## weekly-skipped

"Crate Daily skipped: too few new candidates" means Last.fm and Deezer returned fewer than 30 usable new tracks, and yesterday's mix was kept. If it happens twice in a row, check [lastfm](#lastfm) and [deezer-previews](#deezer-previews).

## lastfm

Last.fm unreachable: wait a day, since Crate Daily still uses Deezer. If it keeps failing with "Invalid API key", create a new key and run `gh secret set -R over-map/crate-data LASTFM_API_KEY`.

## deezer-previews

"Tag coverage is low" or "Deezer call failed".

- Deezer previews are the only audio source. If Deezer stops serving them, Crate keeps working in **degraded mode**: new tracks become `no_preview`, get Deezer's BPM where it exists, and you tag them by hand in the app (Edit tags). Queries treat manual tags exactly like automatic ones.
- Check by hand: `curl -s https://api.deezer.com/track/3135556 | head -c 300` should show a `preview` URL.

## api-changed

"Spotify API changed: … field X missing" (from the weekly `contract` workflow) means an upstream API changed shape.

1. Read the Spotify developer changelog / Deezer API docs.
2. Fix the reading code in `pipeline/crate/spotify.py`, `deezer.py` or `lastfm.py` (and in `ios/CrateKit/Sources/CrateKit/Spotify/` for Spotify), plus the field list in `pipeline/crate/contract.py`.
3. `make test`, then push.

## models

Model files are pinned by checksum in `pipeline/models.lock.json` and cached in Actions.

- If `essentia.upf.edu` is down or the files move, upload the files as assets of a GitHub Release (for example `models-2026.10.1`) and set the repository variable `CRATE_MODELS_MIRROR` to that release's download URL prefix (`https://github.com/<you>/crate/releases/download/models-2026.10.1`). Get the files from `crate/.cache/models` on the Mac after a local run.
- **Never upgrade a model silently.** To upgrade: run `crate report` and keep the result, change the file and checksum in `models.lock.json`, bump `model_version`, run `crate analyse --force-all` locally, run `crate report` again, compare, then commit.

## Restore data

- Any data file at an earlier point: `git log -- data/library.json`, then `git checkout <commit> -- data/library.json`, commit and push.
- Your tag edits live in `data/overrides.json`, and the pipeline never writes to it. Restore it the same way.

## Restore playlists

- Within Spotify's retention window, spotify.com/account → *Recover playlists*.
- Otherwise, `backup/playlists-YYYYMMDD.json` lists every playlist and track URI. To rebuild one, create an empty playlist and add its URIs in batches of 100 with `POST /playlists/{id}/items` (for example with a short Python snippet that uses `crate.spotify.Spotify.call`).

## now-public

Spotify's Web API creates playlists as public and can't make them private (a long-standing API bug). If Crate had to create **Now** or **Crate Daily** (formerly **Weekly Mix**), make each one private once in the Spotify app: open the playlist, then **⋯** → **Make private**. Crate only replaces the contents afterwards, so it stays private.

## Pause and resume

- Pause: `gh workflow disable -R over-map/crate-data nightly && gh workflow disable -R over-map/crate-data daily && gh workflow disable -R over-map/crate-data health && gh workflow disable -R over-map/crate-data contract`
- Resume: the same commands with `enable`.

## Update dependencies

- Dependabot opens monthly grouped pull requests. Merge only when the **tests** check is green.
- Roll back: `git revert <merge commit>` and push.
- Essentia is excluded from Dependabot on purpose. See [models](#models).
- After updating Python dependencies by hand: `cd pipeline && uv lock --upgrade && uv run pytest -q`.

## Migration (one time, M2)

1. Actions → migrate → Run workflow, mode **dry-run**. This commits `backup/playlists-YYYYMMDD.json` and prints counts plus a 6-character code.
2. Read the summary (what gets liked, deleted and unfollowed).
3. **The same day (UTC)**, run migrate with mode **execute** and the code. If anything changed since the dry run, the code won't match and nothing happens. Run the dry run again.
4. Execution likes the tracks, checks every one is in Liked Songs, and only then deletes or unfollows the playlists. It can resume after an interruption.

## Where files live

Everything is under the project folder `11 Music manager/`:

| Path | What | In git? |
|---|---|---|
| `01-…`, `02-…`, `03-…` briefs | design brief, build spec, design update | no (outside the repo) |
| `design/` | Claude Design output | no (outside the repo) |
| `crate/` | this repository | yes |
| `crate/.env` | your secrets for local runs | **never** |
| `crate/.cache/models/` | Essentia model files, checked against `pipeline/models.lock.json` | no: licence and size; `crate` re-downloads them |
| `crate/.cache/embeddings/` | raw 1280-d sound fingerprints, needed for `crate vectors --fit` | no: derived from audio; rebuilt by `crate analyse --force-all` |
| `crate/.cache/http/` | cached Deezer, Last.fm and Discogs responses | no |
| `crate/.cache/spikes/` | outputs of rejected experiments (CLAP) | no |
| `crate/pipeline/.venv/` | Python environment (`uv sync --extra audio`) | no |

Moving the whole `11 Music manager/` folder is safe: every path is relative to the repo. GitHub Actions uses the same `.cache/` layout inside its checkout.
