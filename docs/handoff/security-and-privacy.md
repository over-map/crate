# Security and privacy

How Crate protects your data today, what could go wrong, and what to do, both as a personal app and if it ever goes public. The standard to measure against is the [OWASP Mobile Application Security Verification Standard (MASVS)](https://mas.owasp.org/MASVS/) for the apps and the [GitHub Actions security hardening guide](https://docs.github.com/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions) for the pipeline.

> Not legal advice. GDPR questions for a public app belong with a privacy professional.

---

## 1. What data exists and where it goes

| Data | Stored where | Leaves the phone or Mac to |
|---|---|---|
| Liked Songs list, tags, vibes, presets, Crate Daily (`data/*.json`) | The **private** data repo `over-map/crate-data` (the public code repo never contains it, not even in its history); cached on each phone (App Group on iOS, app-private storage on Android) | GitHub (HTTPS) |
| Spotify login (access + refresh token) | iPhone Keychain (`AfterFirstUnlock`); Android: AES-GCM key in the Android Keystore + private preferences | Spotify (HTTPS) |
| App GitHub token | Same secure storage | GitHub (HTTPS) |
| Cover images | Loaded from Spotify's image CDN, cached by the OS | Spotify CDN (reveals the phone's IP to Spotify, as the Spotify app does) |
| Pipeline secrets | GitHub Actions secrets, `crate/.env` on the Mac | Spotify, Last.fm, Discogs, Deezer, ntfy (from GitHub's runners) |
| Audio | **Never stored**: Deezer previews are analysed in memory on the runner and deleted | — |
| Problem alerts | ntfy.sh topic | ntfy.sh (a public broker: anyone who knows the topic name can read it) |

What Crate deliberately does **not** have: analytics, crash reporting SDKs, ads, tracking, accounts of its own, a server, or any AI service. That is the strongest privacy property it has; keep it that way, or declare every addition in the store privacy labels.

---

## 2. What's already in place

**Public code, private data (since 2026-10-14).** The code is open source in `over-map/crate`, started from a fresh history with no personal data. Your library, edits, playlist backups, Crate Daily and the full history are in the private `over-map/crate-data`, which is also where the scheduled workflows run, so their logs (which name tracks) stay private. The public repo's Actions only run tests on the shared fixtures and build the Android app; its secrets (the signing key) are never exposed to forks or pull requests. The Spotify client ID in the code is public by design (PKCE, no secret), and Spotify's Development mode only lets allow-listed accounts use it.

**Apps**
- **OAuth with PKCE**: no Spotify client secret is in either app; the redirect `crate://spotify-callback` is protected by the PKCE verifier and a `state` check.
- **Secrets in hardware-backed storage** (Keychain / Android Keystore), never in plain files, logs or backups. Android has `allowBackup="false"`. `SpotifyTokens` hides its values in `toString()`.
- **HTTPS only**: no cleartext traffic is allowed on either platform.
- **Blast-radius limit**: every playlist write goes through `NowWriter`'s allow-list, so even a bug or a hijacked request can only overwrite **Now** and **Crate Daily**, never your other playlists.
- **Least data**: the app reads only `data/` from one repo; the GitHub token is fine-grained to that repo with *Contents* only.
- **Android release builds** are shrunk and obfuscated (R8) and signed with a key that is not in git.

**Pipeline and repo**
- Private repo; Actions pinned to commit SHAs; minimal `permissions:` per workflow; Dependabot for updates; lockfiles (`uv.lock`, Gradle wrapper with checksum); secrets only in Actions secrets; `.env`, keystores and provisioning files are git-ignored.
- Every data change is a git commit, so any vandalism or bad write can be seen and reverted (`git revert`, [runbook › Restore data](../runbook.md#restore-data)).
- The one-time migration had a dry-run code and a playlist backup (`backup/`).

---

## 3. Threats and what to do

| Threat | Likelihood today | Impact | Do this |
|---|---|---|---|
| **GitHub account taken over** (phishing, password reuse) | Medium: it's the single key to everything | High: data, workflows, secrets, releases | Use a passkey or hardware security key for 2FA on `over-map`; keep recovery codes in a password manager; review *Settings → Sessions* and *Applications* occasionally |
| **Token leaked** (phone lost, screenshot, pasted in the wrong place) | Low | Medium: the app token can rewrite `data/`; `GH_SECRETS_PAT` can change secrets | Give every token an **expiry** (1 year) and set `APP_PAT_EXPIRES` / `GH_SECRETS_PAT_EXPIRES` so the health check reminds you; revoke at github.com/settings/tokens if a phone is lost. `GH_SECRETS_PAT` currently never expires: change it to 1 year |
| **Phone lost or stolen** | Low | Low–medium | Device passcode + biometrics (the secrets are only readable after first unlock); revoke the app GitHub token and sign out of Spotify (spotify.com → Account → *Sign out everywhere*) |
| **Malicious deep link** (`crate://send?preset=…` from a web page or another app) | Low | Low: it replaces Now with one of your own presets | **Done (2026-10-14):** such links ask "Send … to Now?" first. Only Crate's own Android launcher shortcuts (which carry a private key) and the widgets / Siri send without asking |
| **History rewritten / branch deleted** | Low | High | A ruleset on `main` blocking force pushes and deletion needs GitHub Pro for a private repo (refused on the free plan, 2026-10-14). Until then: keep a local clone current (`git pull` in `crate/`), and occasionally `git clone --mirror` to a drive you control; with Pro, add the ruleset (Settings → Rules) |
| **Compromised dependency or GitHub Action** | Low | High | Keep SHA pinning; merge Dependabot PRs only when `tests` is green; prefer few, well-known dependencies (the iOS app has none); optionally add Gradle dependency verification (`gradle/verification-metadata.xml`) |
| **Secrets in a commit** | Low | High | Never put secrets in files; run `git diff --cached` before committing; if one leaks, **rotate it first**, then clean history |
| **ntfy topic guessed** | Low | Low: someone reads or spams alerts | Use a long random topic (≥ 20 random characters); alerts carry no personal data |
| **Spotify scopes broader than needed** | — | Low | **Done (2026-10-14):** the apps no longer request `user-follow-modify` (only the pipeline's one-time migration needed it). Existing logins keep their old grant until you sign in again |
| **Android signing key lost or stolen** | Low | High (can't update; a thief could sign fake updates) | Back it up encrypted in a password manager; never commit it; it's protected by a long random password |
| **Mac compromised** | Low | High (`.env`, keystore, git credentials) | FileVault on, automatic updates, no admin account for daily use, password manager for `.env` copies |

### Routine security hygiene

- **Monthly**: merge Dependabot PRs; look at GitHub → Settings → *Security* (Dependabot alerts).
- **Yearly**: rotate the tokens (they expire anyway), review Spotify *User Management* and GitHub *Applications*.
- **After an incident**: rotate everything in [accounts-and-secrets.md](accounts-and-secrets.md), check the git log for unexpected commits, revert them.

GitHub's *secret scanning push protection* is free for public repos; for a private repo it needs a paid GitHub plan, so the habits above are your protection.

---

## 4. If Crate goes public: extra requirements

- **Privacy law (GDPR)**: as long as Crate only handles your own data it's personal use. The moment it processes other people's data on infrastructure you control, you're a data controller: publish a privacy policy, have a lawful basis (consent or contract), minimise data, allow access and deletion requests, and sign data processing agreements with any service that stores user data. The "each user runs their own repo" model avoids most of this, because you never hold their data.
- **Store privacy declarations**: Apple *App Privacy* label + `PrivacyInfo.xcprivacy`; Google *Data safety* form. They must match reality: declare every SDK you add.
- **Account deletion**: if you ever add Crate accounts, both stores require in-app account deletion.
- **Security review**: walk through OWASP MASVS (storage, crypto, auth, network, platform, code, resilience) before launch; pay for a penetration test if you hold user data.
- **Certificate pinning** is optional; with only Spotify and GitHub as servers, standard TLS validation is the industry norm.
- **Abuse limits**: rate-limit anything a stranger could trigger, and never let one user's token reach another user's data.
