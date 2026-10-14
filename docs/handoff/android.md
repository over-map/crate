# The Android app

The same app as the iPhone one, without the signing expiry (Android apps installed from an APK don't expire). Kotlin, Jetpack Compose + Material 3, Glance widgets, minSdk 29 (Android 10). Not on Google Play: releases are signed APKs on GitHub.

| Module | What |
|---|---|
| `android/core` | Pure Kotlin port of CrateKit: models, query engine, vibes, Spotify, GitHub, widget sender. Runs the shared `spec/` vectors |
| `android/app` | `app/` (AppModel, data sources, secure storage, Spotify login), `ui/` (theme + components, the CrateUI port), `screens/` (Build, Tracks, Vibes, Daily, Library), `widget/` (Glance), `share/` (share target) |

iPhone feature → Android equivalent: Siri / Shortcuts / Action Button → **launcher shortcuts** (long-press the Crate icon → "Send Dark 138+", one per preset, pinnable to the home screen); Lock Screen widgets → none (phones have no lock-screen widgets); share extension → **share target** ("Share → Crate" in Spotify); Keychain → AES key in the Android Keystore.

## Install on a phone

1. On the phone, open the latest release: github.com/over-map/crate/releases (public, no sign-in) and download `Crate-<version>.apk`.
2. Open it; allow installs from your browser once when Android asks.
3. Open Crate: sign in to Spotify, paste the app GitHub token for `over-map/crate-data` (see [accounts-and-secrets.md](accounts-and-secrets.md)).

Updates install over the old version, keeping the data, as long as they're signed with the same key.

## Release

1. Make sure `tests` is green on `main`.
2. `git tag android-v1.1 && git push origin android-v1.1` (the version name is the tag without `android-v`; the version code is the workflow run number, so it always increases).
3. The `android-release` workflow builds `assembleRelease` signed with the `CRATE_KEYSTORE_*` secrets and creates the GitHub Release with the APK.

Locally: `cd android && ./gradlew :app:assembleRelease` signs with `android/keystore.properties` + `crate-release.jks` (both git-ignored; back them up, see [accounts-and-secrets.md](accounts-and-secrets.md#back-up-the-android-signing-key-now)).

## Build and test

Use Android Studio's JDK: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`, then from `android/`:

| Task | Command |
|---|---|
| Core tests (incl. the shared vectors) | `./gradlew :core:test` |
| Debug build (bundles `data/` as demo data) | `./gradlew :app:assembleDebug` |
| Install on the emulator or a USB phone | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Regenerate design tokens | `python3 android/tools/gen_tokens.py` |

Emulator: Android Studio → Device Manager, or `$ANDROID_HOME/emulator/emulator -avd Android_mobile_Pixel_10`.

Debug launch extras (debug builds only): `adb shell am start -n com.overmap.crate/.MainActivity --ez demo true --es tab build|daily|library --es screen <name>` plus `--ez bottom true` and `--ez gallery true`.

The compile SDK is 37 (current AndroidX libraries require it); install it with `android sdk install platforms/android-37.0 build-tools/37.0.0` (the SDK's `cmdline-tools/latest/bin/android`). Agent brief with conventions for larger changes: `android/AGENT_BRIEF.md`.
