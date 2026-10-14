# Spike: free-text keywords with CLAP (rejected, 2026-10-12)

**Question:** can a text–audio model let the user type any feeling ("California", "vintage", "roadtrip") and get matching tracks?

**Setup:** `laion/larger_clap_general` (Apache-2.0, revision `ada0c23a36c4e8582805bb38fec3905903f18b41`) with transformers 4.57 and torch 2.7.1. Each track was embedded from three 10 s windows of its Deezer preview, about 0.5 s per track. Scripts: `embed.py <library.json> <out.npz>` and `score.py <library.json> <emb.npz> <top_n> "keyword" …`. Outputs (not in git) are in `crate/.cache/spikes/clap/`.

**Findings:**

- `laion/larger_clap_music` is **broken as published**: its text encoder returns the same vector for any input.
- The general checkpoint agrees with Essentia's genre on 87% of 347 tracks (chance is 20%). Sound words work moderately (melancholic, rainy night); places and scenes fail ("California", "Berlin warehouse", "Italian summer"); "hypnotic" was read as ambient, not hypnotic techno.
- Running it would add about 2 GB of PyTorch to Actions. Free text on the phone would need a 250 MB text encoder or a server.

**Decision:** rejected. Use vibes by example, community tags and family consensus instead (see `docs/query-semantics.md`).
