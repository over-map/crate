# Changelog

All notable changes to Crate. Versions follow [semantic versioning](https://semver.org); the iPhone and Android apps share one version number. Format: [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]

## [1.0.2] - 2026-10-14

### Added
- **Lift-off**: when a send to Now succeeds, a light wave in the genre colours blooms out of the Send button and covers of the sent tracks float up and away (iPhone and Android; off with Reduce Motion).
- Open source under the MIT licence. The code is public in `over-map/crate`; personal data moved to the private `over-map/crate-data`.

### Changed
- The apps read and write `over-map/crate-data`. Update both apps; older versions can't find the data.

## [1.0.1] - 2026-10-14

### Security
- `crate://send` links from other apps or web pages now ask "Send … to Now?" before replacing Now. Crate's own widgets, Siri and Android launcher shortcuts still send in one tap.
- The apps no longer request Spotify's `user-follow-modify` permission (only the one-time playlist migration needed it).

### Added
- `LICENSE` (all rights reserved).

## [1.0] - 2026-10-14

First complete version, on iPhone (installed with Xcode) and Android (signed APK, GitHub tag `android-v1.0`).

### Added
- Nightly tagging of Liked Songs: genre family and style (audio + Last.fm + Discogs + Beatport consensus), BPM with half/double detection, Camelot key, energy, vocals.
- Build: genre/style, vibe, tag, BPM (typed or ±3/±6), energy, key, vocals and limit filters; Send replaces the **Now** playlist.
- Vibes defined by example tracks and artists, kept within the seeds' energy range; edited per track in the Edit sheet.
- Presets with home-screen widgets, Siri / Action Button (iPhone) and launcher shortcuts (Android).
- Share from Spotify: tags, More like this → Now, Like.
- **Crate Daily**: 30 new tracks every morning (20 taste, 10 discovery) with feedback from likes.
- Health check with problem-only ntfy alerts; handoff guides in `docs/handoff/`.
