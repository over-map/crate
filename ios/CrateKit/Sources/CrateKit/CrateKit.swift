/// CrateKit: the shared core of the Crate iPhone app, widget and share extension.
///
/// - Data files: ``LibraryFile``, ``OverridesFile``, ``PresetsFile``, ``StateFile``, ``WeeklyFile``,
///   ``HealthFile``, ``TaxonomyFile``, ``VibesFile``, decoded through ``CrateJSON`` (schema guard).
/// - Querying: ``EffectiveTags``, ``Query``, ``QueryEngine``.
/// - Music: ``Camelot``, ``StyleLabel``, ``StyleShade``, ``Mood``; similarity: ``VectorIndex``, ``VibeIndex``.
/// - Services: ``SpotifyAuth``, ``SpotifyClient``, ``NowWriter``, ``GitHubStore``, ``SigningExpiry``.
public enum CrateKit {
    /// Repository paths of the data files.
    public enum Path {
        public static let library = "data/library.json"
        public static let overrides = "data/overrides.json"
        public static let presets = "data/presets.json"
        public static let state = "data/state.json"
        public static let weekly = "data/weekly.json"
        public static let health = "data/health.json"
        public static let taxonomy = "data/taxonomy.json"
        public static let vibes = "data/vibes.json"
        public static let vectors = "data/vectors.bin"
        public static let vectorsIndex = "data/vectors.idx.json"
    }
}
