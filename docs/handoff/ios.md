# The iPhone app

SwiftUI, iOS 17+, Swift 6. Three targets: **Crate** (app), **CrateWidget** (widgets + the `SendPresetToNow` App Intent), **CrateShare** (share extension). Shared code: the Swift package `ios/CrateKit` (models, query engine, vibes, Spotify, GitHub; the `CrateUI` component library) and `ios/Shared` (compiled into the app and the widget).

## Refresh

Free Apple ID signing lasts 7 days. Crate shows the days left on the Library tab and, once **Turn on reminders** has been tapped, notifies the day before and on the expiry day (09:00).

1. Plug the iPhone into the Mac and unlock it.
2. Open `crate/ios/Crate.xcodeproj` in Xcode.
3. Check your iPhone is selected at the top, then press **⌘R**.
4. When Crate opens on the phone, unplug.

Data, presets and the Spotify login stay (they live in GitHub, the App Group and the Keychain). If Crate already expired it won't open; ⌘R brings it back intact. If Xcode asks for device support after an iOS update, let it download.

Limits of a free account: 3 sideloaded apps and 10 App IDs per rolling 7 days (Crate uses 3). Never change the bundle IDs.

## Build and test

| Task | Command |
|---|---|
| CrateKit tests (incl. the shared vectors) | `make ios-test` |
| Simulator build | `make ios-sim` |
| Regenerate the Xcode project or design tokens | `make ios-gen` (never edit `Crate.xcodeproj` by hand; the generator is `ios/tools/gen_xcodeproj.py`) |
| Demo data for the Simulator | `make ios-demo`, then launch with `-demo` |

Debug launch arguments: `-demo`, `-tab build|weekly|library`, `-screen <name>` (each screen area has its own names, e.g. `picker`, `track`, `edit`, `widgets`), `-bottom` (open scrolled to the end), `-gallery` (component gallery).

## How the surfaces get data

- **App**: loads `data/*.json` through the GitHub Contents API with ETags (cache in the App Group), edits write back with SHA-conflict retry.
- **Widgets and Siri**: the app writes each preset's matching track URIs to `Shared/presets.json` in the App Group whenever data changes; a send samples them and replaces Now (`PresetSender`). So the widget reflects the library as of the last time the app was opened.
- **Share extension**: reads the app's cached library from the App Group; "More like this → Now" runs there; "Edit in Crate" opens `crate://track/<id>`.

## Changing things

- **Design tokens:** replace `ios/Design/tokens.json` with a new export and run `make ios-gen` (Android: `python3 android/tools/gen_tokens.py`).
- **A new screen:** add a `.swift` file under `ios/Crate/Screens/<Area>/` (synchronized folders: no project edit needed).
- **New query rules:** see [pipeline.md](pipeline.md#common-changes): change all three engines together.
- Agent brief with conventions for larger changes: `ios/AGENT_BRIEF.md`.
