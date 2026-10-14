# Accounts and secrets

Nothing secret is in git, and no personal data is in the public repo. Secrets live in three places: **GitHub Actions secrets** (pipeline secrets in `over-map/crate-data`, the Android signing key in `over-map/crate`), **`crate/.env`** on the Mac (for running the pipeline by hand) and **each phone's secure storage** (iPhone Keychain, Android Keystore-encrypted storage).

## Accounts

| Account | Used for | Notes |
|---|---|---|
| GitHub `over-map` | the public code repo `over-map/crate` (Actions, Releases with the Android APK) and the private data repo `over-map/crate-data` (data, scheduled workflows, pipeline secrets) | owner account; keep 2FA on |
| Spotify (Premium) | the library; Now and Crate Daily playlists | — |
| Spotify developer app (developer.spotify.com/dashboard) | API access for the pipeline and both apps | Development mode: only users listed under *User Management* can log in. Redirect URIs: `http://127.0.0.1:8888/callback` (pipeline) and `crate://spotify-callback` (apps). Client ID `f3d3994b17844ef395d3be6f1cff401a` is public (PKCE) |
| Last.fm API account | artist tags, Crate Daily candidates | free API key |
| Discogs | release genres/styles | personal access token, 60 requests/min |
| ntfy (ntfy.sh, no account) | problem alerts | the topic name is the secret; subscribe to it in the ntfy app |
| Apple ID, free Personal Team `F2ZGNLP242` | signing the iPhone app (7 days) | set in Xcode → Settings → Accounts |
| Android signing key | signing the Android APK | a file, not an account: see below |

## Secrets and how to rotate them

| Secret | Where | Expires | Rotate |
|---|---|---|---|
| `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET` | Actions secrets, `.env` | no | Dashboard → app → Settings → *Rotate client secret*, then `gh secret set -R over-map/crate-data SPOTIFY_CLIENT_SECRET` and update `.env` |
| `SPOTIFY_REFRESH_TOKEN` | Actions secret, `.env` | rotated automatically by the nightly run (uses `GH_SECRETS_PAT`) | if revoked: [runbook › spotify-token](../runbook.md#spotify-token) |
| `GH_SECRETS_PAT` | Actions secret | set to never expire | fine-grained token, only `crate`, *Secrets: read and write*; `gh secret set -R over-map/crate-data GH_SECRETS_PAT` |
| `LASTFM_API_KEY` | Actions secret, `.env` | no | last.fm/api/accounts → new key → `gh secret set -R over-map/crate-data LASTFM_API_KEY` |
| `DISCOGS_TOKEN` | Actions secret | no | discogs.com/settings/developers → *Generate new token* → `gh secret set -R over-map/crate-data DISCOGS_TOKEN` |
| `NTFY_TOPIC` | Actions secret, `.env`, ntfy app | no | pick a new long random name, `gh secret set -R over-map/crate-data NTFY_TOPIC`, subscribe in the app |
| App GitHub token (one per phone, or shared) | iPhone Keychain / Android secure storage, typed at onboarding | the date you chose | [runbook › github-token](../runbook.md#github-token): fine-grained token, only `crate`, *Contents: read and write*; paste in the app (Library → Settings) |
| App Spotify login | phone secure storage | refreshed automatically; re-login if revoked | Library → Spotify → sign in |
| `CRATE_KEYSTORE_B64`, `CRATE_KEYSTORE_PASSWORD` | Actions secrets of the **public** repo `over-map/crate` (Android release; secrets are never shown to forks or pull requests) | the key is valid 100 years | **don't rotate**: a new key means Android can't update the installed app (uninstall + reinstall, app data lost) |

Optional repository variables `GH_SECRETS_PAT_EXPIRES` and `APP_PAT_EXPIRES` (`YYYY-MM-DD`, `gh variable set -R over-map/crate-data …`) make the health check warn 14 days before a token expires. They're not set today because `GH_SECRETS_PAT` doesn't expire; set `APP_PAT_EXPIRES` to the app token's expiry date.

## Back up the Android signing key now

`crate/android/crate-release.jks` and `crate/android/keystore.properties` (the password) exist only on this Mac. GitHub keeps an encrypted copy as Actions secrets, but secrets can't be read back. If the Mac is lost and there's no copy, future Android builds can't update the installed app. Store both files in your password manager (as attachments) or another safe place.

## Moving to a new Mac

1. Install Xcode, Android Studio, `uv`, `gh` (`gh auth login` as `over-map`).
2. `gh repo clone over-map/crate "11 Music manager/crate"` and `gh repo clone over-map/crate-data "11 Music manager/crate-data"`, then in `crate/`: `ln -s ../crate-data/data data && ln -s ../crate-data/backup backup` (and copy `design/` and the briefs if you want them).
3. Restore `crate/.env` from your password manager (or recreate it from `.env.example` with the secrets above), and `android/crate-release.jks` + `android/keystore.properties`.
4. `make setup` (Python + Essentia), then `make test`.
5. iPhone: Xcode → Settings → Accounts → add the Apple ID; open `ios/Crate.xcodeproj` and ⌘R once with the phone plugged in.

## If the GitHub account is lost

The repo is the database. Keep an occasional local clone up to date (`git pull` in `crate/`). Recreating from a clone: new private repo, `git push`, set every secret above again, update `AppConfig.repoOwner` (`ios/Crate/App/AppConfig.swift`, `android/app/.../app/AppConfig.kt`) and rebuild the apps.
