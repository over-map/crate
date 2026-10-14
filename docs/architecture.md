# Crate architecture

```
┌──────────────────────── GitHub (private repo "crate") ───────────────────────┐
│  pipeline/ (Python)          data/ (JSON, committed by Actions + app)        │
│  ios/ (SwiftUI)              spec/ (shared test vectors)                     │
│                                                                              │
│  Actions:  nightly.yml  → sync → match → analyse → write data/ → commit      │
│            health.yml   → checks → data/health.json → ntfy on problems       │
│            daily.yml    → feedback → candidates → rank → Crate Daily         │
│            contract.yml → upstream API field check → ntfy on change          │
│            migrate.yml  → workflow_dispatch (dry-run | execute)              │
│            tests.yml    → Python + Swift + Kotlin tests, shared vectors      │
│            android-release.yml → tag android-v* → signed APK on a Release    │
└──────────────────────────────────────────────────────────────────────────────┘
        ▲ Spotify Web API (refresh token, Actions secret)           ▲ Deezer, Last.fm, ListenBrainz
        │
┌───────┴──── Apps "Crate": iPhone (SwiftUI) and Android (Compose) ─────────┐
│ Spotify PKCE login · reads data/*.json via GitHub API (fine-grained PAT) │
│ CrateKit query engine → PUT/POST /playlists/{now}/items                  │
│ Widgets · Siri / launcher shortcuts · share sheet · writes overrides etc. │
└──────────────────────────────────────────────────────────────────────────┘
```

No AI at runtime. Tagging uses local Essentia models; ranking uses plain statistics (k-means, cosine, logistic regression in numpy).

## Who writes what

| File | Written by | Read by |
|---|---|---|
| `data/library.json` | Actions only | app, pipeline |
| `data/vectors.bin` + `vectors.idx.json` | Actions only | app ("More like this") |
| `data/pca.npz` | `crate vectors --fit` (by hand) | pipeline |
| `data/weekly.json` (today's Crate Daily; name kept from the weekly era) | Actions only | app |
| `data/taxonomy.json` | Actions / `crate taxonomy` | app, pipeline |
| `data/feedback.json` | Actions only | pipeline |
| `data/state.json` | Actions only | app, pipeline |
| `data/health.json` | Actions only | app (Library tab) |
| `data/overrides.json` | **app only** | pipeline, app |
| `data/presets.json` | **app only** (seeded once) | app, `crate query --preset` |
| `backup/playlists-*.json` | migration only | you |

## Where secrets live

See [runbook › Secrets](runbook.md#secrets).

## Code map

| Path | What |
|---|---|
| `pipeline/crate/query.py`, `library.py` | Query engine and effective-tag merge. The rules are in [query-semantics.md](query-semantics.md). |
| `pipeline/crate/spotify.py` | Spotify client. `PlaylistWriter` is the only code that replaces playlist contents, and only for allow-listed IDs. |
| `pipeline/crate/migrate.py` | The only code that likes/unlikes in bulk and removes playlists. |
| `pipeline/crate/deezer.py`, `http.py` | Deezer matching, rate limits, retries, cache |
| `pipeline/crate/analyse.py`, `essentia_analyzer.py`, `bpm.py`, `energy.py`, `moods.py`, `styles.py`, `keys.py` | Tagging |
| `pipeline/crate/vectors.py`, `taste.py`, `discover.py`, `weekly.py` | Similarity and Crate Daily |
| `pipeline/crate/health.py`, `contract.py`, `notify.py` | Watching itself |
| `ios/CrateKit` | Swift package shared by app, widget and share extension |
| `android/core` | Kotlin port of CrateKit (same names), used by the Android app, widget and share target |
| `android/app` | Compose app: `app/` state and data, `ui/` theme and components, `screens/`, `widget/`, `share/` |
| `spec/*.json` | Test vectors both languages must pass |
