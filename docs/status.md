# Build status

Last updated 2026-10-14.

| # | Milestone | State | What's left / what you need to do |
|---|---|---|---|
| M0 | Repo, Spotify dev app, secrets, `crate auth` | **Done** | — |
| M1 | sync + match + analyse + report | **Done** | — |
| M2 | Full backfill; playlist migration | **Done** | — |
| M3 | `crate query` writes Now | **Done** | — |
| M4 | iPhone app | **Done**, installed with Xcode (free Personal Team, ⌘R weekly) | — |
| M5 | Widgets, Siri / App Intents, share extension, presets | **Done** (iPhone) | — |
| M6 | Crate Daily + feedback + ntfy | **Done** (daily since 2026-10-13) | — |
| M7 | Fallback web page | **Dropped** (user decision 2026-10-14) | — |
| M8 | Handover | **Docs done**: [handoff/](handoff/README.md) | Procedures written down, not rehearsed (user decision) |
| — | Android app (same features, no signing expiry) | **In progress** | Released as signed APKs on GitHub, not Google Play |

## Verified at build time (spec §12)

- **Spotify (Feb 2026 migration guide):** `GET /me/tracks` is still the Liked Songs read. `PUT|DELETE /me/library` and `GET /me/library/contains` take `uris` as a query parameter, **max 40**. Unfollowing a playlist is `DELETE /me/library` with its playlist URI, which needs the `user-follow-modify` scope (added). Search limit max 10.
- **Deezer:** `track/isrc:` and `track/{id}` are public and return `preview`, `bpm` and `isrc`. **The advanced search syntax (`artist:"…" track:"…"`) returned no results in testing**, so plain-text search is used as a fallback.
- **Essentia:** `essentia-tensorflow==2.1b6.dev1389` is the newest release with Python 3.11 wheels for both Linux x86_64 and macOS arm64. Model URLs and checksums are pinned in `pipeline/models.lock.json`, and the full analysis runs locally at about 1.7 s per track.
- **Real-track sanity check:** Daft Punk "Around the World" → House, 121 BPM, party/happy. Massive Attack "Teardrop" → Downtempo/Trip Hop. Slayer "Raining Blood" → Thrash, aggressive 0.999. Pendulum "Slam" → Drum n Bass, 174 BPM.
- **Not yet verified:** refresh-token rotation (handled either way),  Actions minutes on your plan.

## Decisions taken while building

- **Query semantics follow `design/design-notes.md`:** the strongest style decides, and genre and style picks combine with **OR**. This differs from spec §5.5; see [query-semantics.md](query-semantics.md).
- **`hypnotic` has no Essentia model**, so it is never auto-tagged (spec §6.4: "drop it rather than invent one"). It can still be added by hand. The design's mood chips include it, so either accept manual-only or swap it for a modelled mood (e.g. `groovy`, `epic`, `meditative`).
- **Jamendo mood activations are low,** so with `MOOD_THRESHOLD = 0.5` the Jamendo-only moods (dark, dreamy, melancholic, uplifting) will rarely fire. Calibrate `moods.JAMENDO_SCALE` in M1.
- **The PCA basis is fitted once** (`crate vectors --fit`) and then fixed, so new tracks and Weekly candidates share one similarity space and raw 1280-d embeddings never go into git.
- **BPM:** the octave bias only applies at Discogs danceability ≥ 0.95. At 0.6 it flipped "Teardrop" (77 BPM) to 154.
- **Cron times are UTC:** nightly at 01:30 UTC, weekly on Monday at 05:00 UTC.

## Update 2026-10-12

- **Migration done (M2):** 876 playlist tracks moved into Liked Songs, giving 1,015 tracks. 22 owned playlists were deleted and 7 followed ones unfollowed; the backup is in `backup/playlists-20261012.json`.
- **Full library analysed:** 982 tagged, 33 with no preview.
- **BPM:** 94% agree with your old `BPM …` playlists. "BPM -80" is excluded because it was sorted by feel. Uncertain BPMs keep the detected value and will show "≈" in the app.
- **Moods → vibes:** model mood tags were too weak on this library: no hypnotic model, almost no melancholic signal. A free-text test with LAION CLAP worked for some sound words but failed on places and scenes, so it was rejected. The user chose **vibes by example** (no AI service): named vibes from seed tracks/artists, ranked by sound similarity. See [query-semantics.md#vibes](query-semantics.md#vibes). The model mood tags stay in the data but are no longer the main filter.
- **Design impact:** Build's mood chips become vibe chips, and the app needs a "vibe editor" (seeds, never-tracks) plus "Add to vibe…" on the track detail. This must go into the next Claude Design pass.

- **M3 done (2026-10-12):** `crate query` wrote 64 tracks (community Hypnotic, 125–135 BPM with half/double) to a new **Now** playlist, verified by reading it back. Spotify's API ignored `public: false`, so make it private by hand once ([runbook › now-public](runbook.md#now-public)).
