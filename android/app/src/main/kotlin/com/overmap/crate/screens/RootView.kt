package com.overmap.crate.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.AppModel
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.crateModel
import com.overmap.crate.screens.build.BuildScreen
import com.overmap.crate.screens.daily.DailyScreen
import com.overmap.crate.screens.library.LibraryScreen
import com.overmap.crate.screens.library.OnboardingScreen
import com.overmap.crate.screens.tracks.TrackDetailSheet
import com.overmap.crate.ui.components.ComponentGallery
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Space

/** The app's model from any composable. */
@Composable
fun rememberModel(): AppModel = LocalContext.current.crateModel

@Composable
fun RootView() {
    val model = rememberModel()
    if (DebugLaunch.gallery) { ComponentGallery(); return }
    Box(Modifier.fillMaxSize().background(CrateTheme.colors.bgBase)) {
        when (val phase = model.phase) {
            AppModel.Phase.Onboarding -> OnboardingScreen()
            AppModel.Phase.Loading -> {
                LaunchedEffect(Unit) { model.reload() }
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = CrateTheme.colors.textSecondary)
            }
            is AppModel.Phase.Failed -> Column(
                Modifier.align(Alignment.Center).padding(Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text("Couldn't load your library", style = CrateType.headline, color = CrateTheme.colors.textPrimary)
                Text(phase.message, style = CrateType.secondary, color = CrateTheme.colors.textSecondary, textAlign = TextAlign.Center)
            }
            AppModel.Phase.Ready -> if (com.overmap.crate.screens.vibes.isVibesDebugScreen) com.overmap.crate.screens.vibes.VibesDebugRoot() else MainTabs()
        }
    }
}

/** Tabs: Build, Daily, Library (`--es tab build|daily|library` in debug). */
@Composable
fun MainTabs() {
    val model = rememberModel()
    var tab by rememberSaveable { mutableStateOf(DebugLaunch.tab ?: "build") }
    Scaffold(
        containerColor = CrateTheme.colors.bgBase,
        bottomBar = {
            NavigationBar(containerColor = CrateTheme.colors.bgCard, tonalElevation = 0.dp) {
                listOf(Triple("build", "Build", Icons.Filled.Tune), Triple("daily", "Daily", Icons.Filled.WbTwilight),
                       Triple("library", "Library", Icons.Filled.LibraryMusic)).forEach { (id, label, icon) ->
                    NavigationBarItem(selected = tab == id, onClick = { tab = id },
                                      icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                "daily" -> DailyScreen()
                "library" -> LibraryScreen()
                else -> BuildScreen()
            }
        }
    }
    // Debug screen hooks for the Tracks screens (`--es screen track|edit|list…`)
    if (com.overmap.crate.screens.tracks.TracksDebug.isHooked) com.overmap.crate.screens.tracks.TracksDebugRoot()
    // crate://send?preset=<id> from outside Crate: confirm before replacing Now
    model.linkedSendPresetID?.let { id ->
        val name = model.snapshot.presets.presets.firstOrNull { it.id == id }?.name ?: "this preset"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { model.linkedSendPresetID = null },
            title = { Text("Send $name to Now?") },
            text = { Text("A link asked Crate to replace your Now playlist.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { model.sendPreset(id) }) { Text("Send") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { model.linkedSendPresetID = null }) { Text("Cancel") } },
        )
    }
    // crate://track/<id> from the share screen
    model.linkedTrackID?.let { id -> TrackDetailSheet(trackID = id, onDismiss = { model.linkedTrackID = null }) }
}
