package com.overmap.crate.screens.vibes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.theme.CrateTheme

/** Whether `--es screen` asks for a Vibes screen (debug builds only; see [VibesDebugRoot]). */
val isVibesDebugScreen: Boolean get() = DebugLaunch.screen?.startsWith("vibe") == true

/**
 * Opens a Vibes screen directly for emulator checks (`--es screen <name>`; DebugLaunch is ignored in release):
 * `vibe-new`, `vibe-new-seeded` (a track with a vector pre-added), `vibe-<id>` (e.g. `vibe-hypnotic`), `vibes-manager`.
 */
@Composable
fun VibesDebugRoot() {
    val model = rememberModel()
    val screen = DebugLaunch.screen ?: ""
    var open by remember { mutableStateOf(true) }
    if (screen == "vibes-manager") {
        VibesManagerScreen(onBack = {})
        return
    }
    val firstTrack = remember(model.index) {
        val ix = model.index
        ix.ids.firstOrNull { ix.tracks[it]?.title != null && ix.vibeIndex?.vectors?.containsKey(it) == true } ?: ix.ids.firstOrNull() ?: ""
    }
    Box(Modifier.fillMaxSize().background(CrateTheme.colors.bgBase))
    if (!open) return
    when {
        screen == "vibe-new" -> VibeEditorSheet(null, null) { open = false }
        screen == "vibe-new-seeded" -> VibeEditorSheet(null, firstTrack) { open = false }
        screen.startsWith("vibe-") -> VibeEditorSheet(screen.removePrefix("vibe-"), null) { open = false }
    }
}
