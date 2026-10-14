package com.overmap.crate.app

import android.content.Context
import com.overmap.crate.core.CrateJSON
import com.overmap.crate.core.FileGitHubCache
import com.overmap.crate.core.GitHubError
import com.overmap.crate.core.GitHubFile
import com.overmap.crate.core.GitHubStore
import com.overmap.crate.core.HealthFile
import com.overmap.crate.core.LibraryFile
import com.overmap.crate.core.OverridesFile
import com.overmap.crate.core.PresetsFile
import com.overmap.crate.core.StateFile
import com.overmap.crate.core.TaxonomyFile
import com.overmap.crate.core.UrlConnectionTransport
import com.overmap.crate.core.VectorIndex
import com.overmap.crate.core.VibesFile
import com.overmap.crate.core.WeeklyFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the app reads from `data/`, decoded. */
data class DataSnapshot(
    val library: LibraryFile = LibraryFile(),
    val overrides: OverridesFile = OverridesFile(),
    val presets: PresetsFile = PresetsFile(),
    val vibes: VibesFile = VibesFile(),
    val taxonomy: TaxonomyFile = TaxonomyFile(),
    val weekly: WeeklyFile = WeeklyFile(),
    val state: StateFile = StateFile(),
    val health: HealthFile = HealthFile(),
    val vectors: VectorIndex? = null,
    val fromCache: Boolean = false,
)

/** Where data comes from: the private GitHub repo (ETag cache in app storage), or — debug builds only — a bundled
 *  demo copy so screens can be checked without logging in. */
interface DataSource {
    suspend fun load(): DataSnapshot
    suspend fun updateVibes(mutate: (VibesFile) -> VibesFile)
    suspend fun updateOverrides(mutate: (OverridesFile) -> OverridesFile)
    suspend fun updatePresets(mutate: (PresetsFile) -> PresetsFile)
}

class GitHubDataSource(context: Context, token: () -> String) : DataSource {
    private val store = GitHubStore(
        owner = AppConfig.REPO_OWNER, repo = AppConfig.REPO_NAME, token = token,
        transport = UrlConnectionTransport(), cache = FileGitHubCache(File(context.filesDir, "GitHubCache")),
    )

    override suspend fun load(): DataSnapshot = withContext(Dispatchers.IO) {
        coroutineScope {
            val library = async { store.get(AppConfig.Path.LIBRARY) }
            val overrides = async { optional(AppConfig.Path.OVERRIDES) }
            val presets = async { optional(AppConfig.Path.PRESETS) }
            val vibes = async { optional(AppConfig.Path.VIBES) }
            val taxonomy = async { optional(AppConfig.Path.TAXONOMY) }
            val weekly = async { optional(AppConfig.Path.WEEKLY) }
            val state = async { optional(AppConfig.Path.STATE) }
            val health = async { optional(AppConfig.Path.HEALTH) }
            val bin = async { optional(AppConfig.Path.VECTORS_BIN) }
            val idx = async { optional(AppConfig.Path.VECTORS_IDX) }
            val lib = library.await()
            DataSnapshot(
                library = CrateJSON.decode(LibraryFile.serializer(), lib.data, "library.json"),
                overrides = overrides.await()?.let { CrateJSON.decode(OverridesFile.serializer(), it.data, "overrides.json") } ?: OverridesFile(),
                presets = presets.await()?.let { CrateJSON.decode(PresetsFile.serializer(), it.data, "presets.json") } ?: PresetsFile(),
                vibes = vibes.await()?.let { CrateJSON.decode(VibesFile.serializer(), it.data, "vibes.json") } ?: VibesFile(),
                taxonomy = taxonomy.await()?.let { CrateJSON.decode(TaxonomyFile.serializer(), it.data, "taxonomy.json") } ?: TaxonomyFile(),
                weekly = weekly.await()?.let { CrateJSON.decode(WeeklyFile.serializer(), it.data, "weekly.json") } ?: WeeklyFile(),
                state = state.await()?.let { CrateJSON.decode(StateFile.serializer(), it.data, "state.json") } ?: StateFile(),
                health = health.await()?.let { CrateJSON.decode(HealthFile.serializer(), it.data, "health.json") } ?: HealthFile(),
                vectors = run {
                    val b = bin.await(); val i = idx.await()
                    if (b != null && i != null) runCatching { VectorIndex(b.data, i.data) }.getOrNull() else null
                },
                fromCache = lib.fromCache,
            )
        }
    }

    private suspend fun optional(path: String): GitHubFile? =
        try { store.get(path) } catch (_: GitHubError.NotFound) { null }

    override suspend fun updateVibes(mutate: (VibesFile) -> VibesFile) {
        store.updateJSON(VibesFile.serializer(), AppConfig.Path.VIBES, "app: edit vibes", VibesFile(), mutate)
    }

    override suspend fun updateOverrides(mutate: (OverridesFile) -> OverridesFile) {
        store.updateJSON(OverridesFile.serializer(), AppConfig.Path.OVERRIDES, "app: edit tags", OverridesFile(), mutate)
    }

    override suspend fun updatePresets(mutate: (PresetsFile) -> PresetsFile) {
        store.updateJSON(PresetsFile.serializer(), AppConfig.Path.PRESETS, "app: edit presets", PresetsFile(), mutate)
    }
}

/** Bundled copy of `data/` (debug builds, `assets/demo/`). Writes stay in memory so editing screens can be tried. */
class DemoDataSource(private val context: Context) : DataSource {
    private val lock = Mutex()
    private var snapshot: DataSnapshot? = null

    companion object {
        fun isAvailable(context: Context): Boolean = runCatching { context.assets.open("demo/library.json").close() }.isSuccess
    }

    override suspend fun load(): DataSnapshot = lock.withLock { loadLocked() }

    private suspend fun loadLocked(): DataSnapshot = snapshot ?: withContext(Dispatchers.IO) {
        fun file(name: String): ByteArray? = runCatching { context.assets.open("demo/$name").use { it.readBytes() } }.getOrNull()
        DataSnapshot(
            library = file("library.json")?.let { CrateJSON.decode(LibraryFile.serializer(), it, "library.json") } ?: LibraryFile(),
            overrides = file("overrides.json")?.let { CrateJSON.decode(OverridesFile.serializer(), it, "overrides.json") } ?: OverridesFile(),
            presets = file("presets.json")?.let { CrateJSON.decode(PresetsFile.serializer(), it, "presets.json") } ?: PresetsFile(),
            vibes = file("vibes.json")?.let { CrateJSON.decode(VibesFile.serializer(), it, "vibes.json") } ?: VibesFile(),
            taxonomy = file("taxonomy.json")?.let { CrateJSON.decode(TaxonomyFile.serializer(), it, "taxonomy.json") } ?: TaxonomyFile(),
            weekly = file("weekly.json")?.let { CrateJSON.decode(WeeklyFile.serializer(), it, "weekly.json") } ?: WeeklyFile(),
            state = file("state.json")?.let { CrateJSON.decode(StateFile.serializer(), it, "state.json") } ?: StateFile(),
            health = file("health.json")?.let { CrateJSON.decode(HealthFile.serializer(), it, "health.json") } ?: HealthFile(),
            vectors = run {
                val b = file("vectors.bin"); val i = file("vectors.idx.json")
                if (b != null && i != null) runCatching { VectorIndex(b, i) }.getOrNull() else null
            },
        )
    }.also { snapshot = it }

    override suspend fun updateVibes(mutate: (VibesFile) -> VibesFile) = lock.withLock {
        val s = loadLocked(); snapshot = s.copy(vibes = mutate(s.vibes))
    }

    override suspend fun updateOverrides(mutate: (OverridesFile) -> OverridesFile) = lock.withLock {
        val s = loadLocked(); snapshot = s.copy(overrides = mutate(s.overrides))
    }

    override suspend fun updatePresets(mutate: (PresetsFile) -> PresetsFile) = lock.withLock {
        val s = loadLocked(); snapshot = s.copy(presets = mutate(s.presets))
    }
}
