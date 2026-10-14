# Brief for screen agents (Crate Android app)

Repo: this repository (the `crate/` folder). Android project: `android/` (Gradle 9.8.1 wrapper, AGP 9.4.1, Kotlin 2.4.21, Jetpack Compose + Material 3, Glance, minSdk 29, package `com.overmap.crate`). Don't commit or push.

**The Android app is the same app as the iOS one**, so the iOS app is the spec. Read the matching Swift screens in `ios/Crate/Screens/<Area>/` and port them faithfully: same layout, copy, states, counts, behaviour and edge cases. The iOS screens already include every user decision since the design (for example: vibes are edited in the track's Edit sheet; energy blocks are buttons with − / +; Build has typed BPM and ±3/±6/Any chips; Daily rows name the closest liked track; the heart toggles like/unlike). Design reference (look and feel): the design handoff in `../design/` (next to the repo; not public) (`README.md`, `design-notes.md`, `design-system.md`, `Crate Update v2.dc.html` wins over older files). Use Android-native patterns where iOS uses iOS-native ones: `ModalBottomSheet` for sheets, Material 3 controls themed by `CrateTheme`, system back, `NavigationBar` tabs (already in `screens/RootView.kt`). **Leave out the app-signing expiry** (iOS-only: the Library "App expires" card and its reminders).

What already exists (read before writing code):
- `android/core` (`com.overmap.crate.core`): 1:1 Kotlin port of iOS CrateKit — models, QueryEngine, VibeIndex, VectorIndex, Tags, Camelot, StyleLabel/StyleShade, SpotifyClient, NowWriter, GitHubStore, CrateLink, SpotifyLink, PresetSender/SharedStore. Names match Swift.
- `android/app/src/main/kotlin/com/overmap/crate/ui/` — theme (`CrateTheme`, `CrateTheme.colors`, generated `Tokens.kt`: CrateType, Space, Radius, Size, Family, familyColor, energyColor, camelotColor, PresetColor, VibeSymbols; `crateIcon(sfSymbolName)` maps SF Symbol names to Material icons) and components (Compose ports of iOS CrateUI: StyleChip, GenreSummaryChip, FamilyMiniChip, VibeChip, TagChip, ConfidenceLine, BPMText, EnergyMeter, EnergyRangeControl, RangeSlider, CamelotBadge, CamelotWheel, CoverImage, CompactTagLine, TrackRow, LikeButton, PresetTile, PresetPill, PrimaryButton, PanelLabel, crateCard, ChipFlow, chipChrome …). Use them. If one is missing or needs a variant, add it in a NEW file in your own screens package; don't edit shared files (other agents use them in parallel) — list small fixes in your report instead.
- `android/app/src/main/kotlin/com/overmap/crate/app/` — `AppModel` (Compose snapshot state; get it with `rememberModel()` from `screens/RootView.kt`): `snapshot`, `index` (LibraryIndex: tracks, overrides, effective, ids, families, tagCounts, vibeMembers, vibeIndex, count(q), matchingIDs(q), tagSuggestions), `build` + `updateBuild { }`, `matchCount`, `sendState`, `send()`, `sendURIs()`, `count(applying)`, `editVibes/editOverride/editPresets` (pure functions returning the new value), `spotify` (SpotifyService: connect(context), like/unlike/contains), `isDemo`, `useDemoData()`, `saveGitHubToken`, `validateGitHub`, `reload`, `openNow()`, `open(uri)`, `linkedTrackID`, `scope`. `BuildState` (immutable; `query(allStyles)`, `BuildState.from(query, allStyles)`), `DebugLaunch` (intent extras). **Don't edit `app/` or `MainActivity.kt`/`CrateApplication.kt`**; if you need more model API, add Kotlin extension functions on `AppModel` in a file inside YOUR package (e.g. `screens/tracks/AppModelTracks.kt`) — the Swift `AppModel+<Area>.swift` and `TrackPresentation.swift` files show what to port.
- Screen entry points with agreed names (other agents call them; keep names and parameters, you may add parameters with defaults): `screens/build/BuildScreen()`, `screens/daily/DailyScreen()`, `screens/library/LibraryScreen()`, `screens/library/OnboardingScreen()`, `screens/tracks/TrackDetailSheet(trackID, onDismiss)`, `screens/tracks/EditTrackSheet(trackID, onDismiss)`, `screens/tracks/TrackListScreen(title, trackIDs, onBack)`, `screens/vibes/VibeEditorSheet(vibeID: String?, seedTrackID: String?, onDismiss)`, `screens/vibes/VibesManagerScreen(onBack)`, `screens/library/PresetsManagerScreen(onBack)`, `screens/library/SavePresetSheet(presetID: String?, onDismiss)`, `screens/build/GenrePickerSheet(onDismiss)`, `screens/build/KeyPickerSheet(onDismiss)`. If you call another area's screen that is still a stub or missing, create a minimal placeholder in THAT package only if it doesn't exist yet, with exactly that signature; the owner will replace it.

Data in demo mode is the user's real library (1,015 tracks; debug builds bundle `data/` as assets). Use real counts from `model.index`, never hardcoded numbers.

Build & verify (from `android/`, always with this JAVA_HOME):
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug            # Gradle serialises parallel builds; if it says another build holds the lock, wait
```
Screenshots (one shared emulator; take the lock so agents don't collide): 
```
export ANDROID_HOME=~/Library/Android/sdk; ADB=$ANDROID_HOME/platform-tools/adb
# lock: until mkdir /tmp/crate-emulator.lock 2>/dev/null; do sleep 5; done   … and rmdir it when done
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell am force-stop com.overmap.crate
$ADB shell am start -n com.overmap.crate/.MainActivity --ez demo true --es tab build --es screen <name>
$ADB shell cmd uimode night yes|no      # dark / light
$ADB exec-out screencap -p > ../.cache/screens/android-<area>-<state>-<dark|light>.png
```
The emulator (AVD `Android_mobile_Pixel_10`) is started by the lead; if `$ADB devices` shows none, start it with `$ANDROID_HOME/emulator/emulator -avd Android_mobile_Pixel_10 -no-window -no-audio -no-snapshot-save &` and wait for `adb wait-for-device`. Add `screen` hooks (read `DebugLaunch.screen`) in your own screens to open sheets/states directly for screenshots. Look at every screenshot, compare with the iOS screenshots/design, and fix until it matches (dark and light). Accessibility: font scale 2.0 (`adb shell settings put system font_scale 2.0`, reset to 1.0 after), 48 dp targets, TalkBack labels (contentDescription/semantics) mirroring the iOS VoiceOver labels.

Report: files changed, screenshots taken, what's done vs. not, deviations from iOS, and any API you'd like added to shared files.
