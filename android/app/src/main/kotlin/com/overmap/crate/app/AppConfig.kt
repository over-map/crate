package com.overmap.crate.app

import com.overmap.crate.core.CrateIdentity

/** Fixed configuration. Nothing here is secret: the Spotify client ID is a public identifier (PKCE, no secret),
 *  and the GitHub token lives in encrypted storage, entered at onboarding. */
object AppConfig {
    const val REPO_OWNER = "over-map"
    const val REPO_NAME = "crate-data" // private data repo; the code is public in over-map/crate
    val spotifyClientID: String get() = CrateIdentity.spotifyClientID
    val spotifyRedirect: String get() = CrateIdentity.spotifyRedirect

    /** Data files the app reads (paths in the repo). */
    object Path {
        const val LIBRARY = "data/library.json"
        const val OVERRIDES = "data/overrides.json"
        const val PRESETS = "data/presets.json"
        const val VIBES = "data/vibes.json"
        const val TAXONOMY = "data/taxonomy.json"
        const val WEEKLY = "data/weekly.json"
        const val STATE = "data/state.json"
        const val HEALTH = "data/health.json"
        const val VECTORS_BIN = "data/vectors.bin"
        const val VECTORS_IDX = "data/vectors.idx.json"
    }
}
