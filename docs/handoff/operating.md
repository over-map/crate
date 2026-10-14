# Operating Crate

## What runs by itself

The scheduled workflows run in the **private data repo `over-map/crate-data`** (they check out the public code each time); `tests` and `android-release` run in the **public `over-map/crate`**. See `ops/data-repo/README.md`.

| When (UTC) | Workflow | What it does | If it fails |
|---|---|---|---|
| 01:30 daily | `nightly` | Sync Liked Songs, analyse new tracks (Deezer preview + Essentia), Last.fm and Discogs tags, family consensus, vectors; commits `data/` | ntfy alert → [runbook › nightly-failed](../runbook.md#nightly-failed) |
| 03:30 daily | `daily` | Builds **Crate Daily** (20 taste + 10 discovery), reads yesterday's likes as feedback | ntfy → [runbook › weekly-failed](../runbook.md#weekly-failed) |
| 06:00 daily | `health` | Checks freshness, tokens, coverage; writes `data/health.json` (shown in the apps' Library tab) | ntfy on any problem |
| Wednesday 07:00 | `contract` | Checks Spotify/Deezer/Last.fm still return the fields Crate needs | ntfy → [runbook › api-changed](../runbook.md#api-changed) |
| Every push | `tests` | Python, Swift and Kotlin tests (all three run the shared vectors) and an Android build | red check on the commit |
| Tag `android-v*` | `android-release` | Signed Android APK on a GitHub Release | see [android.md](android.md#release) |
| Monthly | Dependabot | Grouped dependency PRs | merge only when `tests` is green |

ntfy only speaks when something is wrong. Silence means healthy.

## Your routine

| How often | Task | How |
|---|---|---|
| Weekly (when Crate reminds you) | Re-sign the **iPhone** app (free Apple signing lasts 7 days) | Plug the iPhone into the Mac, open `ios/Crate.xcodeproj`, press ⌘R. Crate notifies you the day before and on the day. See [ios.md](ios.md#refresh) |
| Monthly | Merge Dependabot PRs | GitHub → Pull requests; merge the green ones |
| Monthly | Glance at the apps' Library tab | "All good" means nothing to do |
| When a token expiry date nears | Rotate it | [accounts-and-secrets.md](accounts-and-secrets.md); the health check warns 14 days ahead if the expiry variables are set |
| After an iOS or Android major update | Open both apps once; for iOS let Xcode install device support | — |
| After liking music outside Spotify's Liked Songs | Nothing: only Liked Songs count | — |

The Android app needs no routine: an installed APK doesn't expire.

## When something looks wrong

1. Open the app's **Library** tab: the health card names the problem and links to the runbook section.
2. Check GitHub → Actions for a red run and read its log.
3. Fix with the matching [runbook](../runbook.md) section. Each section is copy-paste.
4. To stop everything temporarily: [runbook › Pause and resume](../runbook.md#pause-and-resume).

Common cases:

- **Now didn't change after Send:** Spotify login expired in the app → Library → Spotify → sign in again.
- **App says it can't load the library:** the app's GitHub token expired → [runbook › github-token](../runbook.md#github-token).
- **A track is in the wrong genre or vibe:** open it → Edit → fix genre, style, energy or vibes → Save. Edits are stored in `data/overrides.json` / `data/vibes.json` and win over automatic tags.
- **Crate Daily is empty or stale:** [runbook › weekly-skipped](../runbook.md#weekly-skipped).
- **A playlist shows as public:** Spotify creates playlists public; make it private once in Spotify (⋯ → Make private). The apps' Library tab shows a reminder card.

## Data you can edit by hand

All in `data/`, committed to git (every change is reversible with `git revert`). Prefer the apps; edit by hand only for bulk changes.

| File | Written by | Safe to hand-edit? |
|---|---|---|
| `presets.json` | apps | yes (JSON schema: see `ios/CrateKit/.../Presets.swift`) |
| `vibes.json` | apps | yes |
| `overrides.json` | apps | yes |
| `beatport.json` | you | yes: per-track Beatport genres entered by hand |
| everything else | Actions | no: regenerated nightly |
