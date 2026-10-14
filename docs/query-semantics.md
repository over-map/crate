# Query semantics

The query engine exists twice: `pipeline/crate/query.py` (Python, CLI and tests) and `CrateKit/Query` (Swift, the iPhone app). Both must pass every case in `spec/query_vectors.json`, and CI fails if they don't.

These rules replace build spec §5.5 where the two differ. The changes come from the design decisions recorded in `design/design-notes.md`.

## Effective tags

A track's effective tags are its `auto` tags merged with `overrides.tracks[id]`. The override wins field by field.

| Field | Effective value | Unknown when |
|---|---|---|
| `bpm`, `key`, `energy` | override value, else `auto` value | neither is set |
| `vocal` | override `true`/`false`, else `auto.vocal ≥ 0.5` (`VOCAL_THRESHOLD`) | neither is set |
| moods | `{m : auto.moods[m] ≥ 0.5}` ∪ `moods_add` − `moods_remove` | no `auto.moods` and no `moods_add` |
| primary style | see below | no candidate |
| primary genre | see below | no primary style and no `genres_add` |

**Primary style ("the strongest style decides").**
1. If `styles_add` is non-empty, its first element is the primary style.
2. Otherwise, take the `auto.styles` ranked by activation (descending; ties broken by canonical label, ascending), drop any style in `styles_remove` and any style whose genre is in `genres_remove`, and keep the first one left.

**Primary genre.** If `styles_add` is empty and `genres_add` is non-empty, the primary genre is `genres_add[0]`, and the primary style is kept only if it belongs to that genre. Otherwise, the primary genre is the parent of the primary style.

A style's genre is the part of its canonical label before `---` (`Electronic---Techno` → `Electronic`).

No activation threshold applies to the primary style. Every tagged track has exactly one primary style, so style counts add up to genre counts. `STYLE_THRESHOLD` (0.2, top 3) is kept only to choose which secondary styles the UI displays.

## Matching

A field with no constraint never filters. A constrained field fails for a track whose value for that field is unknown, so untagged tracks never match a constrained field.

| Query field | Rule |
|---|---|
| `genres_include`, `styles_include` | If either is non-empty, the track must have primary genre ∈ `genres_include` **or** primary style ∈ `styles_include`. The two groups combine with OR: in the UI, "all styles ticked" becomes a genre and "some ticked" becomes styles, and several genres combine with OR. |
| `genres_exclude` / `styles_exclude` | The primary genre / style must be known and not in the set. The UI never emits these; the engine supports them. |
| `bpm {min,max,half_double}` | `min ≤ bpm ≤ max`. With `half_double`, `bpm·2` or `bpm/2` in range also matches. |
| `energy {min,max}` | Inclusive. A full-range `1–10` is still a constraint; the UI sends `null` for "Any". |
| `moods_include` | All present (AND). |
| `moods_exclude` | None present. Unknown moods fail. |
| `key {camelot, compatible}` | Equal to `camelot`, or with `compatible` in {`nX`, `(n±1 mod 12)X`, `n(other letter)`}, with 12 wrapping to 1. |
| `vocal` | `any` (no constraint), `vocal` or `instrumental`. |
| `limit` | Default 100. Result = random sample of `min(limit, matches)` tracks, shuffled with Fisher–Yates. The RNG is injectable; the vectors check the match set and the count, not the order. |

## Vibes

A vibe is a user-named mood defined by example, stored in `data/vibes.json`. Only the app writes that file; `crate vibes --seed` creates the starter set once.

```json
{"schema": 1, "order": ["hypnotic"], "vibes": {"hypnotic": {"name": "Hypnotic", "icon": "circle.dotted.circle", "seeds": ["<track id>"], "artists": ["Kangding Ray"], "exclude": [], "share": 0.15}}}
```

Members are computed from the sound-similarity vectors (`vectors.bin`):

1. **Seeds.** Explicit seeds = `seeds` ∪ every track with an artist equal (case-insensitive) to one in `artists`. Tag seeds = every track with a normalised tag in `seed_tags` (see Tags). Only tracks with a vector count.
2. **Tag seeds must also sound like the vibe.** Rank once with explicit ∪ tag seeds (rule 3), and drop the tag seeds outside that pass's top k. Explicit seeds always stay. If no seeds are left, the vibe has no members.
3. **Score** = cos(track, P) − `VIBE_NEGATIVE_WEIGHT` (0.2) × cos(track, N). P = normalise(mean of the normalised seed vectors). N is the same over the `exclude` tracks that have a vector; with no such tracks, there is no N term. Rank by score (descending; ties broken by track ID, ascending). k = `ceil(share × number of tracks with a vector)`; `share` defaults to `VIBE_DEFAULT_SHARE` = 0.2.
4. **Members** = top k ∪ seeds − `exclude`. An excluded track is not replaced one for one: its "pull" in rule 3 already lowers similar tracks.
5. **Energy must fit too.** The 32-d sound vector barely encodes energy, so on its own "Energetic" pulled in quiet tracks. Take the seeds' effective energies (an override wins). With at least `VIBE_ENERGY_MIN_SEEDS` (3) known, ignore any seed more than `VIBE_ENERGY_OUTLIER` (4) from the median of the other seeds, and keep only top-k tracks whose energy is within `VIBE_ENERGY_MARGIN` (1) of the remaining seeds' min–max. Seeds and tracks with unknown energy always stay. Correcting a track's energy therefore also moves it in or out of vibes.

Query field `vibes_include: [vibe id, …]` means the track is a member of **every** listed vibe (AND, like moods). An unknown vibe ID matches nothing. Tracks without a vector (`no_preview`, `pending`) never match a vibe.

## Tags

`tracks[id].tags` in `library.json` is the merged, normalised tag list from every outside source: Last.fm artist tags (weight ≥ 10, top 15 per artist), Discogs release genres and styles (`data/discogs.json`), and hand-entered Beatport genres (`data/beatport.json`). `crate tags` builds it; nightly runs it. A track whose artists have no source at all has no `tags` key (unknown). `tracks[id].tag_sources` = `{lastfm|discogs|beatport: [normalised tags]}` credits each tag to its source (display only; queries use `tags`).

Normalisation is in `spec/tag_synonyms.json`: NFKD, drop accents, lower-case, remove apostrophes, `&` → ` and `, each of `- _ / . ,` → space, collapse spaces, trim, then map synonyms (italy → italian, brasil → brazilian, hip-hop → hip hop, …).

| Query field | Rule |
|---|---|
| `tags_include: [term, …]` | Every normalised term must be in the track's normalised tags (AND). Unknown tags fail. |
| vibe `seed_tags: [term, …]` | Adds every track with any of these normalised tags to the vibe's seeds (rule 1 under Vibes). |

## Families (consensus)

`tracks[id].family` is one of the style families in `data/taxonomy.json` → `families` (techno, house, downtempo, electronica, disco, indie dance, trance, breaks & bass, hip hop, rock & indie, jazz, latin & brazilian, reggae & dub, funk & soul, pop & cantautori, folk & world, classical). The pipeline computes it in `crate consensus` (`pipeline/crate/consensus.py`):

1. If the user's override has `family`, use it. (The app sets it when the user edits a track's style.)
2. Otherwise, each source votes for families: the audio model (strongest style), Discogs release styles and genres, the artist's top 8 Last.fm tags, and Beatport genres entered by hand. If at least 2 votes go to a family other than the audio's, that family wins.
3. Otherwise, the family of the audio's strongest style.
4. Otherwise (no audio), the family with the most votes.

`tracks[id].consensus` keeps the evidence: `family`, `votes`, `sources`, `audio_agrees`, `by_source`. The app shows it as confidence ("3 of 3 sources agree").

**Effective family** = override `family`, else `tracks[id].family`. The engines don't recompute it.

| Query field | Rule |
|---|---|
| `family_styles: {family: [style, …]}` | What the picker sends when only some styles of a family are ticked: the track matches if its effective family is that family **and** its primary style is in the list. A style counts only under the family it was ticked in (the Techno style appears under 6 families in this library). Same "any of" group as below. |
| `families_include` | Belongs to the "any of" group with `family_styles`, `genres_include` and `styles_include`: the track matches if its effective family is in `families_include` **or** its primary genre is in `genres_include` **or** its primary style is in `styles_include`. In the picker, ticking a whole family sends `families_include`, and ticking some of its styles sends `family_styles` (not the flat `styles_include`, which is kept for old presets). An unknown family fails. |

`data/vibes.json` also has `order: [vibe id, …]`, the Build order. The app's vibes manager reorders it, and vibes missing from `order` go at the end, by name. `data/taxonomy.json` has `family_names` (UI names, sentence case). Weekly tracks have `reason` (the one-line "why this track?").
