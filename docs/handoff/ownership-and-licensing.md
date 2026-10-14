# Copyright, ownership and licences

Who owns what in Crate, which third-party terms apply, and how to protect your ownership.

> Not legal advice. For anything commercial, ask an IP lawyer.

---

## 1. Who owns Crate

| Part | Owner | Notes |
|---|---|---|
| The code (`pipeline/`, `ios/`, `android/`, workflows) | You, as the person who commissioned and directed it | Most of it was written by an AI (Claude) under your direction. Anthropic's terms assign the outputs to you. Copyright law in the EU and US protects **human** creative input; purely AI-generated material may get thin or no copyright protection, while your choices, selection, arrangement and edits do count. In practice: treat the code as yours, keep the git history (it documents your direction), and rely on contracts and licences rather than copyright alone |
| The design (`design/`, app icon) | You | Created with Claude Design under your direction; same note on AI output |
| Your data (`data/`) | You | Built from your Spotify library plus third-party metadata (see below) |
| Spotify content (track names, artists, cover art, audio) | Spotify, labels and artists | Used under the [Spotify Developer Terms](https://developer.spotify.com/terms); not yours to redistribute |
| Third-party libraries and models | Their authors | Used under their licences (below) |

### Check your employment contract

Many employment contracts give the employer rights to anything created **on company equipment or time**, or related to the company's business. If Crate was built on a work laptop or during working hours, read the intellectual-property clause of your contract or ask HR or legal for a written confirmation that Crate is a personal project. Do this **before** publishing or monetising it.

### Make ownership explicit in the repo

1. `LICENSE`: **MIT** (since 2026-10-14, when the code went public): anyone may use, copy, modify and redistribute it, keeping the copyright notice "Copyright (c) 2026 Filippo Imberti". The licence covers the code only; your data stays private in `over-map/crate-data`. Essentia stays AGPL-3.0 and the MTG models CC BY-NC-SA, so whoever runs the pipeline inherits those terms. If you ever open-source it, choose a licence compatible with the AGPL-licensed Essentia (see below), e.g. AGPL-3.0, or remove Essentia first.
2. Put the copyright line in the store listings ("© 2026 <your name>") and in the app's About screen.
3. Keep the GitHub account and the repo under your personal control (not an employer's organisation); you already use the personal `over-map` account.
4. Register the app name as a trademark only if you plan to publish under it; at least search EUIPO, USPTO and the WIPO Global Brand Database first ("Crate" is a common word, and other apps use it).

---

## 2. Third-party terms Crate depends on

| Component | Licence / terms | What it means for Crate |
|---|---|---|
| **Essentia** (`essentia-tensorflow`, the analysis library) | **AGPL-3.0** | Fine for personal use. If you distribute the pipeline or run it as a network service for others, you must offer its complete source under the AGPL |
| **Essentia / MTG models** (genre, mood, BPM heads) | **CC BY-NC-SA 4.0** | Non-commercial only, with attribution; derivatives under the same licence. No paid app or service built on these tags without other models or a licence from MTG (Universitat Pompeu Fabra) |
| **Spotify Web API** | [Developer Terms](https://developer.spotify.com/terms), [Design guidelines](https://developer.spotify.com/documentation/design) | No machine-learning training on Spotify content; attribute Spotify, link back to Spotify for content, don't alter cover art, use the Spotify logo only as the guidelines allow. Crate never touches Spotify audio (analysis uses Deezer previews) |
| **Deezer API** (30 s previews) | [Deezer API terms](https://developers.deezer.com/termsofuse) | Personal, non-commercial use; previews are analysed and deleted, never stored or redistributed |
| **Last.fm API** | [API terms](https://www.last.fm/api/tos) | Non-commercial use is free; attribute Last.fm where tags are shown (the apps label them "LFM") |
| **Discogs API** | [API terms](https://www.discogs.com/developers); database data is CC0 | Attribute Discogs ("DSC"); respect rate limits; images are not CC0 |
| **Beatport** | Genres typed in by hand from Beatport's public pages | Facts like genre labels aren't copyrightable; don't scrape or copy Beatport content at scale |
| **Apple frameworks, Swift** | Apple SDK agreement; Swift Apache-2.0 | Standard |
| **Android, Kotlin, AndroidX, Jetpack Compose, Glance, kotlinx** | Apache-2.0 | Keep the licence notices; add an "Open-source licences" screen before a store release |
| **Coil** (Android image loading), **OkHttp** | Apache-2.0 | Same |
| **Python dependencies** (numpy, httpx, …) | BSD / MIT / Apache-2.0 | Permissive; `uv.lock` lists them |
| **GitHub Actions** (checkout, setup-uv, setup-java, setup-gradle) | MIT | Standard |

Attribution belongs in the README (done) and, for a store release, in the apps' About screen.

---

## 3. Protecting it in practice

- **Public code, private data**: the code is MIT open source; the data repo is private, collaborators only by explicit invitation.
- **Backups**: GitHub plus your local clone; occasionally `git clone --mirror` to a drive you control.
- **Signing keys** prove releases are yours: the Android key (back it up) and, for the App Store, your Apple Developer account.
- **Store accounts in your own name** (or your company's, if you create one); never in an employer's developer account.
- If someone copies Crate's code or name: GitHub and both stores have IP complaint processes (DMCA / takedown forms). The git history, signed releases and dated store listings are your evidence.
