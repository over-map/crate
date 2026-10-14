package com.overmap.crate.screens.tracks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.overmap.crate.BuildConfig
import com.overmap.crate.app.AppModel
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.core.EffectiveTags
import com.overmap.crate.core.TrackStatus
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.theme.Family

/**
 * `--es screen track | track-approx | track-expanded | track-override | edit | list` (+ `--ez bottom true`), debug
 * builds only: opens the Tracks screens directly for screenshots (port of iOS `TracksDebugRoot`).
 *
 * Hosted by [TrackDetailSheet]: launch with any `crate://track/<id>` link, e.g.
 * `am start -n com.overmap.crate/.MainActivity --ez demo true --es screen edit -d crate://track/<id>`; the hook
 * picks its own track. A root host can also call [TracksDebugRoot] directly when [TracksDebug.isHooked].
 */
object TracksDebug {
    val screens = setOf("track", "track-approx", "track-expanded", "track-override", "edit", "list")
    private var consumed = false

    val isHooked: Boolean get() = BuildConfig.DEBUG && DebugLaunch.screen in screens

    /** The hooked screen, once per process. */
    fun take(): String? {
        if (!isHooked || consumed) return null
        consumed = true
        return DebugLaunch.screen
    }

    /** Long sheets open scrolled to the end. */
    val scrollToBottom: Boolean get() = isHooked && DebugLaunch.bottom
}

@Composable
fun TracksDebugRoot(screen: String = DebugLaunch.screen ?: "track", onDismiss: () -> Unit = {}) {
    val model = rememberModel()
    var prepared by remember { mutableStateOf(screen != "track-override") }
    val id = remember(model.index) { pick(model, screen) } ?: return
    if (screen == "track-override") LaunchedEffect(Unit) { prepareOverride(model, id); prepared = true }
    if (!prepared) return
    when (screen) {
        "list" -> {
            val ids = model.index.ids.filter { model.index.effective[it]?.family == Family.TECHNO.data }
            TrackListScreen("${ids.size} tracks", ids, onBack = onDismiss, subtitle = "Techno", showsSend = true)
        }
        "edit", "track-override" -> EditTrackSheet(id, onDismiss)
        "track-expanded" -> TrackDetailSheet(id, onDismiss, startsExpanded = true)
        else -> TrackDetailSheet(id, onDismiss, startsExpanded = false)
    }
}

private fun pick(model: AppModel, screen: String): String? {
    val tagged = model.index.tracks.entries.filter { it.value.status == TrackStatus.tagged }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.value.title ?: "" })
    return when (screen) {
        "track-approx" -> tagged.firstOrNull { (it.value.auto?.bpmConf ?: 1.0) < 0.5 }?.key
        "track-expanded", "track-override", "edit" ->
            tagged.firstOrNull { (it.value.consensus?.sources ?: 0) >= 3 && (it.value.tags?.size ?: 0) >= 7 }?.key ?: tagged.firstOrNull()?.key
        else -> tagged.firstOrNull()?.key
    }
}

/** `track-override`: an in-memory override (demo data) so the edited states show. */
private suspend fun prepareOverride(model: AppModel, id: String) {
    val t = model.index.tracks[id] ?: return
    val ranked = EffectiveTags.rankedStyles(t.auto?.styles.orEmpty())
    val other = model.index.families.map { it.family }.firstOrNull { it != t.family } ?: "disco"
    val removed = ranked.drop(1).firstOrNull()
    val bpm = t.auto?.bpm?.let { Math.round(it / 2).toDouble() }
    model.editOverride(id) { o ->
        o.copy(family = other, stylesRemove = removed?.let { listOf(it) } ?: o.stylesRemove, bpm = bpm, energy = 8)
    }
}
