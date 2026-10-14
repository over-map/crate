# The pipeline

Python 3.11 package `crate` in `pipeline/` (CLI: `uv run crate --help`). It runs in GitHub Actions; you can run any step locally after `set -a; source .env; set +a`.

## Daily flow

1. **sync** — Liked Songs → `data/library.json` (new tracks `pending`; unliked ones removed, their overrides kept).
2. **analyse** — for each pending track: Deezer 30 s preview (ISRC, then search), Essentia models → styles, BPM (octave-corrected), Camelot key, energy 1–10, vocal; the preview is deleted immediately. Fingerprint → `.cache/embeddings`, 32-d vector → `data/vectors.bin`.
3. **tags** — Last.fm artist tags + Discogs release genres/styles + hand-entered Beatport genres (`data/beatport.json`) → normalised `tracks[id].tags` and `tag_sources`.
4. **consensus** — each track's **family** (17 families, `data/taxonomy.json`): user override, else ≥2 outside votes against the audio, else the audio's strongest style. Evidence in `tracks[id].consensus` (the apps' "2 of 3 sources agree").
5. **daily** (separate workflow) — Crate Daily: 20 taste picks from 5 rotating families + 10 discovery picks weighted to small or absent families, ranked by sound similarity and a feedback model trained on what you liked; nothing from the last 30 days repeats.

Rules for matching (genres OR styles, vibes AND, tags AND, BPM half/double, compatible keys, the energy band for vibes): [../query-semantics.md](../query-semantics.md).

## Common changes

| Change | How |
|---|---|
| Add or fix a vibe | In an app: Library → Vibes, or a track's Edit → Vibes. By hand: `data/vibes.json` (seeds, artists, seed_tags, exclude, share) |
| Add or change a preset | In an app (bookmark on Build, or Library → Presets), or `data/presets.json` |
| Correct a track's tags | App → track → Edit. Stored in `data/overrides.json` |
| Enter Beatport genres | `data/beatport.json` (per track, never per artist) |
| Re-analyse tracks | [runbook › Re-run or force analysis](../runbook.md#re-run-or-force-analysis) |
| Change a matching rule | Change Python (`pipeline/crate/query.py`, `vibes.py`), Swift (`ios/CrateKit`), **and** Kotlin (`android/core`) together; add cases to `spec/query_vectors.json`; document it in `docs/query-semantics.md`. CI fails if the three disagree |
| Change Crate Daily's mix | `pipeline/crate/discover.py` (split, weights) and `weekly.py` (`RECENT_DAYS`, `FEEDBACK_AFTER_DAYS`) |
| Change the schedule | the `cron` lines in `.github/workflows/*.yml` (UTC) |

## Tests

`make test` (or `cd pipeline && uv run pytest -q`), `make lint`. The models are pinned in `pipeline/models.lock.json` and downloaded to `.cache/models` on first use; see [runbook › models](../runbook.md#models) before upgrading Essentia.

## Licences

The Essentia models are CC BY-NC-SA 4.0: personal, non-commercial use only. Deezer previews are analysed and never stored.
