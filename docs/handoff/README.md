# Crate handoff

Everything someone needs to run, repair and change Crate without the person (or the AI) who built it. Start here.

## What Crate is

A personal system around one Spotify account:

- **The pipeline** (Python, GitHub Actions, free) tags every Liked Song every night, builds **Crate Daily** every morning, and checks its own health.
- **The apps** (iPhone and Android, the same features) filter the library by genre, vibe, tags, BPM, energy, key and vocals, and replace the **Now** playlist with the result. Widgets, launcher shortcuts / Siri, and the Spotify share sheet do the same without opening the app.
- **The data** is plain JSON in the **private** GitHub repo `over-map/crate-data` (`data/`). The **code is public** and open source (MIT) in `over-map/crate`. There is no server and no database.

No AI runs anywhere at runtime. Total cost: zero beyond Spotify Premium.

## The guides

| Guide | Read it when |
|---|---|
| [operating.md](operating.md) | You look after Crate: the routine calendar, alerts, what to check |
| [accounts-and-secrets.md](accounts-and-secrets.md) | A token expires, you move to a new Mac, or something must be rotated or recovered |
| [pipeline.md](pipeline.md) | You change tagging, vibes, presets, Crate Daily, or the workflows |
| [ios.md](ios.md) | You build, refresh or change the iPhone app |
| [android.md](android.md) | You build, release or change the Android app |
| [publishing.md](publishing.md) | You ship an update, or want Crate on the App Store or Google Play (release process, industry standards, exact store steps, what blocks a public release) |
| [security-and-privacy.md](security-and-privacy.md) | You want to know what data goes where, and how to keep Crate from being attacked or vandalised |
| [ownership-and-licensing.md](ownership-and-licensing.md) | Copyright, who owns the code and design, and the third-party terms Crate depends on |

Deeper references: [../runbook.md](../runbook.md) (copy-paste fixes, one section per alert), [../architecture.md](../architecture.md) (how the parts fit), [../query-semantics.md](../query-semantics.md) (the matching rules both apps and the pipeline implement).

## Where everything lives

| What | Where |
|---|---|
| Code, docs, tests, Android releases | GitHub **`over-map/crate` (public, MIT)** = local clone `11 Music manager/crate/` |
| Your data (`data/`, `backup/`), the scheduled workflows and the pipeline secrets | GitHub **`over-map/crate-data` (private)** = local clone `11 Music manager/crate-data/`; `crate/data` and `crate/backup` are links to it |
| Design (Claude Design handoff, v2) | `11 Music manager/design/` (not in git) |
| Original briefs (design brief, build spec, design update) | `11 Music manager/01-…`, `02-…`, `03-…` (not in git) |
| Local secrets for running the pipeline by hand | `crate/.env` (git-ignored) |
| Promo video | `11 Music manager/Crate demo.mp4` (not in git: it shows tracks from your library) |
| Android release signing key | `crate/android/crate-release.jks` + `keystore.properties` (git-ignored; **back them up**, see accounts-and-secrets.md) |
| Model files, sound fingerprints, HTTP cache | `crate/.cache/` (git-ignored; rebuildable) |

## The three rules that keep it working

1. **Shared test vectors are the contract.** `spec/query_vectors.json` and `spec/camelot_vectors.json` are run by Python, Swift and Kotlin in CI. If one fails, fix that engine; never edit a vector to make code pass.
2. **Only Now and Crate Daily are ever overwritten.** Every playlist write goes through an allow-list (`PlaylistWriter` in Python, `NowWriter` in Swift and Kotlin).
3. **Never change the app identifiers** (`com.overmap.crate`, `.widget`, `.share`, the App Group `group.com.overmap.crate`). Free Apple accounts may create only 10 App IDs per week, and Android updates only install over an app with the same ID and signing key.
