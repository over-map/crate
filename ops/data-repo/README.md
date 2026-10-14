# The private data repo

Crate keeps code and personal data apart:

- **`over-map/crate`** (public): code, docs, tests, Android releases.
- **`over-map/crate-data`** (private): `data/` (library, tags, vibes, presets, Crate Daily), `backup/`, and the scheduled workflows in this folder, with the pipeline's secrets.

Each scheduled run checks out the public code, checks out the data repo into `private/`, links `data` and `backup` into the code checkout, runs the pipeline, and commits the changes back to the data repo. The apps read and write the data repo directly with a fine-grained token.

## Set up your own (for a fork)

1. Create a **private** repo, e.g. `you/crate-data`, with empty `data/` and `backup/` folders.
2. Copy `.github/workflows/*.yml` from here into it.
3. In the data repo: Settings → Secrets and variables → Actions:
   - secrets as in [docs/runbook.md › Secrets](../../docs/runbook.md#secrets) (`SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `SPOTIFY_REFRESH_TOKEN`, `LASTFM_API_KEY`, `NTFY_TOPIC`, `GH_SECRETS_PAT` with *Secrets: read and write* on the data repo, optional `DISCOGS_TOKEN`);
   - variable `CRATE_CODE_REPO` = your public fork (e.g. `you/crate`) if it isn't `over-map/crate`.
4. In the apps, set `repoOwner` / `repoName` (`ios/Crate/App/AppConfig.swift`, `android/app/.../app/AppConfig.kt`) to your data repo, and use your own Spotify client ID (`CrateIdentity.spotifyClientID`).
5. Locally, link the data into your code checkout: `ln -s ../crate-data/data data && ln -s ../crate-data/backup backup` (both are git-ignored in the code repo).
