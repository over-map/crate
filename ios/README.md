# Crate for iPhone

## CrateKit

`CrateKit/` is the Swift package shared by the app, the widget and the share extension. It holds everything
that isn't UI:

- **Models**: Codable types for every file in `data/` (`library.json`, `overrides.json`, `presets.json`,
  `state.json`, `weekly.json`, `health.json`, `taxonomy.json`). Decode them with `CrateJSON.decode`, which
  refuses a newer `schema` with an "update the app" error instead of crashing.
- **Query engine**: `EffectiveTags` (auto tags merged with overrides), `Query` and `QueryEngine`
  (`matches`, `count`, `run`). The rules are in `docs/query-semantics.md` and are shared with the Python
  pipeline through `spec/query_vectors.json`.
- **Music**: `Camelot` (key conversion, names, compatible keys), `StyleLabel`, `StyleShade` (FNV-1a), `Mood`.
- **Similarity**: `VectorIndex` reads `vectors.bin` (float16) and `vectors.idx.json` for "More like this".
- **Vibes**: `VibesFile` (`data/vibes.json`, written by the app) and `VibeIndex`, which works out each vibe's
  members from its seeds (tracks, artists, and `seed_tags` that also sound right), with excluded tracks pulling the ranking away. `Query.vibesInclude` then filters on them (see `docs/query-semantics.md#vibes`).
- **Tags**: `TagNormaliser` / `Tags.normalise` normalise community tags (`library.json → tracks[id].tags`) for
  `Query.tagsInclude` and a vibe's `seed_tags`. The synonym table is bundled as
  `Sources/CrateKit/Resources/tag_synonyms.json`, a copy of `spec/tag_synonyms.json`. After you edit the spec
  file, copy it again: a test fails if the two differ by a single byte.
- **Families**: the pipeline's consensus writes `tracks[id].family` and its evidence (`Consensus`). The
  effective family is the override's `family`, else the track's. `Query.familiesInclude` and
  `Query.familyStyles` (styles ticked under one family) are ORed with genres and styles. `TaxonomyFile.displayName(family:)`
  gives the UI name, and `VibesFile.ordered` gives the vibes in Build order.
- **Spotify**: `PKCE`, `SpotifyAuth` (authorize URL and token requests), `SpotifyClient`, and `NowWriter`,
  the only code that changes playlist items. It refuses any playlist that isn't on its allow-list (Now and
  Weekly Mix).
- **GitHub**: `GitHubStore` reads `data/` through the Contents API with ETags, caches files (in the App Group
  container in the app), and writes with read-modify-write, retrying up to three times on a conflict.
- **Signing**: `SigningExpiry` reads `ExpirationDate` from `embedded.mobileprovision`.

The package has no third-party dependencies and makes no network calls in tests. All HTTP goes through the
`HTTPTransport` protocol, and the tests use a fake one.

### Running the tests

The package also builds for macOS 14, so the tests run on the Mac without a simulator:

```sh
cd ios/CrateKit
swift build
swift test
```

The query and Camelot tests read `spec/query_vectors.json` and `spec/camelot_vectors.json` from the repository,
so run them from a full checkout. If a shared vector fails, fix the engine (Swift or Python), not the vector.
The vectors are the contract between the two.

## App targets

Targets: `Crate` (app), `CrateWidget` (WidgetKit + App Intents), `CrateShare` (share extension), all depending on `CrateKit`. `ios/Shared` is compiled into both the app and the widget: the `SendPresetToNow` intent runs in the app for Siri, Shortcuts and the Action Button, and in the widget for its buttons. The bundle IDs are fixed and must never change, because free accounts may only create 10 App IDs per 7 days:

| Target | Bundle ID (fixed, chosen 2026-10-12) |
|---|---|
| Crate | `com.overmap.crate` |
| CrateWidget | `com.overmap.crate.widget` |
| CrateShare | `com.overmap.crate.share` |
| App Group | `group.com.overmap.crate` |

## Widgets, Siri and the share sheet (M5)

How they get data without the app running: whenever the library, overrides, vibes or presets change, the app writes `Shared/presets.json` into the App Group. It holds each preset's name, display lines and **every matching track URI**, because the widget can't run the query engine on the full library. A send samples `limit` of those URIs, shuffles them and replaces Now through `NowWriter` (`PresetSender` in CrateKit). The last send's state is in `Shared/status.json`, so all widgets show the same Sending / Sent / Error. Spotify tokens come from the shared Keychain group.

So a preset reflects the library **as of the last time the app was opened**. Tracks tagged overnight show up in the widget after you next open Crate.

| Surface | What it does |
|---|---|
| Small widget | One preset (long-press → Edit Widget → Preset). The whole widget sends; then "Listen on Spotify" for 30 s |
| Medium widget | The first four "In widget" presets from Library → Presets; empty cells say "Add preset" |
| Lock Screen circular / rectangular | One preset each; single-colour |
| Siri / Shortcuts / Action Button | "Crate, send Warehouse", or the "Send preset to Now" action in Shortcuts (Action Button → Shortcut) |
| Share sheet | Spotify → ⋯ → Share → Crate: tags, confidence and vibes, **More like this → Now**; "Edit in Crate" opens the track, where Edit changes its tags and vibes. Tracks not in the library get a Like button |
| `crate://` links | `now` (open Now in Spotify), `open`, `track/<id>`, `send?preset=<id>` |

Debug: `-screen widgets` shows every widget in every state (`-from Sent` starts the list there).

## Personal Team capability test (M4)

Tested on the iPhone with a free Apple ID:

| Capability | Result | If it fails |
|---|---|---|
| App Group shared container (widget reads the app's cache) | **Pass** (2026-10-13) | Widget buttons become `crate://send?preset=<id>` deep links that open the app |
| Keychain access group (widget/share read Spotify tokens) | **Pass** (2026-10-13) | Same deep-link fallback |
| Interactive widget runs `SendPresetToNow` without opening the app | _check on device (M5 checklist)_ | Deep link |
| Share extension | _check on device (M5 checklist)_ | "Open in Crate" handoff via `crate://track/<id>` |
| Local notification 24 h before signing expiry | _check on device_ | — |

## M5 device checklist

After installing the new build (⌘R in Xcode with the iPhone plugged in), open Crate once so it writes the preset file.

1. Home Screen → long-press → **+** → Crate → add **Preset** (small) and **Presets** (medium).
2. Tap the small widget: it shows "Sending…", then "Sent · N tracks". Open Spotify: Now has new tracks. After 30 s the widget is back to "Send to Now".
3. Tap a medium cell: the other cells dim while it sends.
4. Lock Screen → Customize → add the circular and rectangular widgets; tap one.
5. Say "Crate, send Warehouse". Siri replies "Sent N tracks to Now." Then in Shortcuts, check the "Send preset to Now" action exists (for the Action Button).
6. In Spotify, share a track from your library → Crate: the tag card shows; tap **More like this → Now**. Share a track you haven't liked: the Like button adds it.

## Install and refresh (Xcode)

Free Apple ID signing lasts 7 days (chosen 2026-10-13, after AltStore's sign-in kept failing with -80045).

To install or refresh: plug the iPhone into the Mac, open `ios/Crate.xcodeproj` in Xcode, check that your iPhone is selected at the top, and press **⌘R**. Unplug once Crate opens. Your data, presets and Spotify login stay as they are (they live in GitHub, the App Group and the Keychain); only the signature is renewed.

Crate's Library tab shows the days left. After you tap **Turn on reminders** there once, it sends a notification the day before expiry and another on the expiry day itself (09:00, or earlier when Crate expires in the early morning).

Limits: 3 sideloaded apps and 10 App IDs per rolling 7 days; Crate uses 3 App IDs (app, widget, share extension). Push notifications and iCloud are not available, and Crate doesn't use them. Never change the bundle IDs.
