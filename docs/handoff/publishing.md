# Releases, updates and publishing to the stores

Today Crate is a **personal** app: the iPhone build is installed from Xcode, the Android build from a GitHub Release. This guide covers how releases work now, how to ship updates, the industry standards to follow, and the exact steps to publish on the Apple App Store and Google Play, including what has to change first.

> Not legal advice. Store rules and API terms change; check the linked official pages before you act.

---

## 1. Read this first: what blocks a public release today

Crate was designed for one person. Three things must change before strangers can use it from a store:

| Blocker | Why | What it would take |
|---|---|---|
| **Spotify API access** | The Spotify developer app is in *Development mode*. Since 2026 Spotify allows only a handful of allow-listed users per developer app (reported as 5), and *Extended quota* (unlimited users) is only granted to registered organisations with at least 250,000 monthly active users ([Spotify, April 2025](https://developer.spotify.com/blog/2025-04-15-updating-the-criteria-for-web-api-extended-access), [February 2026](https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security)). | Either stay private (you plus up to ~4 friends added under *User Management*), or a "bring your own Spotify app" model where each user registers their own free developer app and pastes its client ID into Crate. |
| **Single-user data** | The apps read and write *your* private GitHub repo with *your* token, and the pipeline tags *your* library in *your* GitHub Actions. | Each user needs their own copy: a "Use this template" repo with the workflows, their own secrets, and an onboarding that asks for their repo name. Or a real backend (costs money, and you'd then process other people's data, see [security-and-privacy.md](security-and-privacy.md)). |
| **Licences of the tagging stack** | The Essentia models are CC BY-NC-SA 4.0 (non-commercial) and the `essentia` library is AGPL-3.0; Deezer previews, Last.fm and Discogs have API terms. | A free, non-commercial app where each user runs the pipeline themselves is the safest fit. Selling the app or running the tagging as a service needs other models or licences. See [ownership-and-licensing.md](ownership-and-licensing.md). |

Also: **"Crate" is a common name**. Search both stores and the trademark registers (EUIPO, USPTO, WIPO Global Brand Database) and pick a unique store name before you build a listing.

---

## 2. How releases work today

| | iPhone | Android |
|---|---|---|
| Build | Xcode on the Mac (`ios/Crate.xcodeproj`, scheme Crate) | GitHub Actions in the public repo (`.github/workflows/android-release.yml`) |
| Install | Plug in, ⌘R | Download `Crate-<version>.apk` from GitHub Releases, open it |
| Signing | Free Apple ID (Personal Team), **7 days** | Release key `android/crate-release.jks` (in Actions secrets), valid 100 years |
| Version | `VERSION` in `ios/tools/gen_xcodeproj.py` (`MARKETING_VERSION`) | the tag: `android-v1.2` → versionName `1.2`; versionCode = workflow run number |
| Update | Pull the latest `main`, ⌘R | Tag a new version; install the new APK over the old one |

### Releasing an update today

1. Make the change on a branch, open a pull request, let the `tests` check go green, merge into `main`.
2. Add a line to [`CHANGELOG.md`](../../CHANGELOG.md) under a new version heading.
3. **Android:** `git tag android-v1.1 && git push origin android-v1.1`. The workflow builds, signs and publishes the release with the APK. Install it on the phone over the old one.
4. **iPhone:** if the version changed, edit `VERSION` in `ios/tools/gen_xcodeproj.py` and run `make ios-gen`; then plug in and ⌘R.
5. If the data format changed (`data/*.json`), keep it **additive** (new optional fields only) so older app versions keep working, and update both apps in the same release.

---

## 3. Industry standards for releases and updates

| Practice | What it means | In Crate |
|---|---|---|
| **Semantic versioning** | `MAJOR.MINOR.PATCH`: major = breaking, minor = features, patch = fixes. Both platforms show the same version | Use the same number on iOS (`MARKETING_VERSION`) and Android (tag) |
| **Monotonic build numbers** | Every uploaded build has a higher build number (iOS `CFBundleVersion`, Android `versionCode`), even for the same version | Android uses the run number; iOS: bump `CURRENT_PROJECT_VERSION` per upload (or let CI set it) |
| **Trunk-based development + tags** | Short-lived branches, `main` always releasable, a tag per release | Already the model (`android-v*` tags; add `ios-v*` when you upload to Apple) |
| **Changelog and release notes** | A human-readable `CHANGELOG.md` ([keepachangelog.com](https://keepachangelog.com)); store "What's new" text from it | `CHANGELOG.md` at the repo root |
| **CI/CD** | Every merge is tested; releases are built and signed by CI, never on a laptop | GitHub Actions. For the stores add [fastlane](https://fastlane.tools) (`deliver`/`pilot` for Apple, `supply` for Google) with the App Store Connect API key and a Google Play service account stored as Actions secrets |
| **Beta channel first** | Every release goes to testers before the public: TestFlight (Apple), internal/closed testing tracks (Google) | — |
| **Staged rollout** | Release to a percentage of users, watch crashes, then widen | Apple *phased release* (7 days: 1 → 2 → 5 → 10 → 20 → 50 → 100 %); Google staged rollout (e.g. 5 → 20 → 50 → 100 %) with *Halt rollout* if needed |
| **Crash and quality monitoring** | Watch crash and ANR rates after each release | App Store Connect → Xcode Organizer crashes, Play Console → Android vitals (bad-behaviour thresholds: 1.09 % user-perceived crash rate, 0.47 % ANR rate). Third-party SDKs (Crashlytics, Sentry) add data collection: declare them in the privacy labels |
| **Forced / soft update** | A minimum supported version the app checks at launch | Possible later via a `min_app_version` field in `data/state.json` |
| **Release cadence and hotfixes** | Regular releases (every 2–4 weeks), a fast path for urgent fixes | Apple offers *expedited review* for critical bugs |
| **Reproducible, signed builds** | Pinned toolchains and dependencies, keys only in a secret store | Gradle wrapper, `uv.lock`, pinned Actions SHAs, keys in Actions secrets |

---

## 4. Apple App Store: exact steps

### One-time setup

1. **Enrol in the Apple Developer Program**: [developer.apple.com/programs](https://developer.apple.com/programs/), 99 USD/year. As an *individual* (your name is shown as the seller) or an *organisation* (needs a legal entity and a D-U-N-S number).
2. In the EU you must declare **trader status** in App Store Connect (Digital Services Act). A trader's address, phone and email are shown on the store page.
3. Sign the agreements in App Store Connect → *Business* (the Free Apps agreement is enough for a free app).
4. In Xcode → Settings → Accounts, select the paid team. In `ios/tools/gen_xcodeproj.py` set `TEAM` to the new team ID and run `make ios-gen`. The bundle IDs stay `com.overmap.crate`, `.widget`, `.share`; register them under the paid team (Xcode does this with automatic signing) with the **App Groups** capability. If `group.com.overmap.crate` is still held by the free team, remove it there first or pick a new group ID in all three entitlements files and `CrateIdentity.appGroupPrefix`.
5. Create the app record: App Store Connect → *Apps* → **+** → New App: platform iOS, name (unique on the store), primary language, bundle ID `com.overmap.crate`, SKU (any internal ID).

### What Apple requires before the first submission

| Item | Notes for Crate |
|---|---|
| **Privacy policy URL** | A public web page (a GitHub Pages page works). Describe exactly the data in [security-and-privacy.md](security-and-privacy.md) |
| **App Privacy label** (App Store Connect → App Privacy) | Today Crate collects nothing for the developer: no analytics, no ads, no tracking. Data goes from the phone to Spotify and the user's own GitHub repo. Answer "Data not collected" only if that stays true in the store version |
| **Privacy manifest** `PrivacyInfo.xcprivacy` | Required: add one to the app, widget and share targets declaring *required-reason APIs* Crate uses (UserDefaults, file timestamps) and `NSPrivacyTracking = false` |
| **Export compliance** | Crate uses only standard HTTPS: set `ITSAppUsesNonExemptEncryption = NO` in `Config/Crate-Info.plist` |
| **Age rating** | Questionnaire; Crate rates 4+ (no user-generated content, no web browsing) |
| **Screenshots** | 6.9-inch iPhone screenshots (1320 × 2868) are required; iPad only if you enable iPad |
| **Texts** | Name (30), subtitle (30), description, keywords (100), support URL, category Music, copyright line ("2026 <your name>") |
| **Review access** | Reviewers must be able to use the app: give them a working Spotify test account and GitHub token in *App Review Information*, or a demo mode in the release build |
| **Guidelines** | [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/), especially 2.1 (complete), 4.2 (minimum functionality), 5.1 (privacy), 5.2 (intellectual property: use Spotify's name and logo only per [Spotify's design guidelines](https://developer.spotify.com/documentation/design)) |

### Build, test and submit

1. Set the version (`VERSION` in the generator, then `make ios-gen`) and a new build number.
2. Xcode → select *Any iOS Device (arm64)* → **Product → Archive**.
3. Organizer → the archive → **Distribute App** → *App Store Connect* → *Upload* (automatic signing creates the distribution certificate and profiles).
4. The build appears in App Store Connect → **TestFlight** after processing (minutes to an hour). Add yourself as an internal tester (up to 100, no review); external testers (up to 10,000) need a short Beta App Review.
5. App Store Connect → the app → the version → select the build, fill in *What's New*, then **Add for Review** → **Submit**. Review usually takes about a day.
6. Choose the release: *manual*, *automatic after approval*, or **phased release** (recommended).

### Updates on the App Store

Same steps 1–6 with a higher version and build number. Users get updates automatically. To ship from CI instead of Xcode: an App Store Connect API key (App Store Connect → Users and Access → Integrations) as Actions secrets, and a `macos` workflow running `fastlane` (`build_app`, `upload_to_testflight`, `deliver`), or Apple's Xcode Cloud.

With a paid account, signing lasts a year and the 7-day refresh disappears; remove the signing card and reminders (`SigningExpiry`, `RefreshNotifications`) from the store build.

---

## 5. Google Play: exact steps

### One-time setup

1. **Create a Play Console developer account**: [play.google.com/console](https://play.google.com/console), a one-time 25 USD fee, identity verification (government ID; for organisations a D-U-N-S number). Personal accounts must also verify a physical device.
2. Declare **trader status** for the EU (shown on the listing).
3. **Personal accounts created after November 2023 must run a closed test with at least 12 testers for 14 continuous days** before production access ([Play Console help](https://support.google.com/googleplay/android-developer/answer/14151465)).
4. **Developer verification**: Google now requires apps on certified Android devices to come from verified developers, even outside Play. It's in force in Brazil, Indonesia, Singapore and Thailand since 30 September 2026 and rolls out globally from 2027 ([Google](https://support.google.com/android-developer-console/answer/16561738)). A Play account counts as verified. For the GitHub APK, register the package name and signing key in the Android Developer Console before it applies in your country, or users will have to use the "advanced" install flow.
5. Create the app: Play Console → **Create app**: name, default language, app (not game), free, accept the policies.

### Signing for Play

Play distributes **App Bundles** (`.aab`) and signs them with the *app signing key* (Play App Signing). You upload with an *upload key*.

- To let users move between the GitHub APK and Play without reinstalling, upload the **existing** `crate-release.jks` as the app signing key (Play Console → Setup → App signing → *Use a different key* → follow the PEPK steps), and create a separate upload key.
- Otherwise let Google generate the signing key and use `crate-release.jks` as the upload key; GitHub and Play builds then can't update each other.

Build the bundle: `cd android && ./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` (add a `bundleRelease` step to the release workflow when you go to Play).

### What Google requires before the first release

| Item | Notes for Crate |
|---|---|
| **Privacy policy URL** | Same page as for Apple |
| **Data safety form** | Declare what's collected and shared. Today nothing is collected by the developer; data is sent to Spotify and GitHub at the user's request; encrypted in transit |
| **App access** | Credentials for reviewers (Spotify test account, GitHub token) or a demo mode |
| **Content rating** | IARC questionnaire (Music, no user content) |
| **Target audience** | 18+ or 13+; not "designed for children" |
| **Ads declaration** | No ads |
| **Store listing** | Name (30), short description (80), full description (4,000), icon 512 × 512, feature graphic 1024 × 500, at least 2 phone screenshots |
| **Target API level** | Play requires targeting a recent API level (within about a year of the latest); Crate targets 36 |
| **Policies** | [Developer Program Policies](https://play.google.com/about/developer-content-policy/), incl. intellectual property (Spotify branding) and the [Spotify Developer Terms](https://developer.spotify.com/terms) |

### Release tracks

1. **Internal testing**: up to 100 testers by email, available within minutes. Upload the `.aab` here first.
2. **Closed testing**: the mandatory 12 testers × 14 days for new personal accounts.
3. **Open testing** (optional): anyone can join.
4. **Production**: Play Console → Production → Create release → upload the bundle → release notes → **Start rollout** with a percentage (staged rollout). Review takes from hours to a few days.

### Updates on Google Play

Increase `versionCode` (CI does this), set `versionName`, build `bundleRelease`, upload to internal testing, promote to production with a staged rollout. Automate with a Google Play service account (Play Console → Users and permissions) stored as an Actions secret and `fastlane supply` or the Gradle Play Publisher plugin. Users get updates automatically; the [Play In-App Updates API](https://developer.android.com/guide/playcore/in-app-updates) can prompt them.

---

## 6. Checklist before any public release

- [ ] Spotify access solved (stay private, or "bring your own Spotify app")
- [ ] Per-user data (template repo + onboarding for the user's own repo), no access to your repo
- [ ] Unique store name checked against both stores and trademark registers
- [ ] Privacy policy published; App Privacy label and Data safety form filled in
- [ ] `PrivacyInfo.xcprivacy` and `ITSAppUsesNonExemptEncryption` added (iOS)
- [ ] Spotify branding per Spotify's guidelines (logo, "Listen on Spotify", link back to Spotify for every track and cover)
- [ ] Licence review of the tagging stack done ([ownership-and-licensing.md](ownership-and-licensing.md))
- [ ] Security review against [security-and-privacy.md](security-and-privacy.md) (OWASP MASVS)
- [ ] TestFlight and Play internal testing builds tried on real devices
- [ ] Release notes in `CHANGELOG.md`; staged rollout planned
