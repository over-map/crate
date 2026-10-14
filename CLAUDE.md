# Crate: notes for AI assistants (optional)

Everything needed to run Crate is in README.md and docs/. This file isn't required.

- Build spec: `../02-build-spec-claude-code.md`. Design: `../design/` (design-notes.md takes precedence over the brief).
- Query rules: docs/query-semantics.md. Change them only together with spec/query_vectors.json, then make both `pytest` and `swift test` pass. Never edit a vector to make code pass.
- Python 3.11 + uv + ruff (line length 130). Swift 6, strict concurrency, no third-party dependencies.
- Destructive Spotify calls only go through `PlaylistWriter`/`NowWriter` (allow-listed Now/Weekly Mix) and `migrate.py`.
- Never log tokens. Never put audio in the repo.
