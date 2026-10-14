package com.overmap.crate.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.BuildState
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.core.HealthLevel
import com.overmap.crate.core.HealthProblem
import com.overmap.crate.core.StyleLabel
import com.overmap.crate.core.TrackStatus
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.screens.tracks.TrackDetailSheet
import com.overmap.crate.screens.vibes.VibesManagerScreen
import com.overmap.crate.ui.components.CompactTagLine
import com.overmap.crate.ui.components.CountText
import com.overmap.crate.ui.components.PanelLabel
import com.overmap.crate.ui.components.TrackRow
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.familyColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One of Crate's playlists that Spotify reports as public. */
data class PublicPlaylist(val id: String, val name: String) {
    companion object {
        /** "Crate Daily is public in Spotify." / "Now and Crate Daily are public in Spotify." */
        fun sentence(list: List<PublicPlaylist>): String {
            val names = list.map { it.name }
            return when (names.size) {
                0 -> "Spotify creates them as public playlists."
                1 -> "${names[0]} is public in Spotify."
                else -> "${names.dropLast(1).joinToString(", ")} and ${names.last()} are public in Spotify."
            }
        }
    }
}

private enum class LibraryRoute { VIBES, PRESETS }

/**
 * Library tab (design 6t, 3g): search, privacy hint, health status, sources, your music, settings. Port of iOS
 * `LibraryView` without the app-signing card (iOS-only).
 *
 * Debug hooks (`--es tab library --es screen …`): `library-search`, `library-hint`, `presets`, `preset-edit`,
 * `save-preset`, `onboarding`, `onboarding-2`, `onboarding-3`.
 */
@Composable
fun LibraryScreen() {
    val screen = DebugLaunch.screen ?: ""
    if (screen.startsWith("onboarding")) {
        // Debug only: full screen over the tabs, as RootView shows it before login.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            OnboardingScreen(startStep = screen.removePrefix("onboarding-").toIntOrNull() ?: 1)
        }
        return
    }
    val model = rememberModel()
    var route by rememberSaveable {
        mutableStateOf(if (screen == "presets" || screen == "preset-edit" || screen == "save-preset") LibraryRoute.PRESETS else null)
    }
    when (route) {
        LibraryRoute.VIBES -> VibesManagerScreen(onBack = { route = null })
        LibraryRoute.PRESETS -> {
            PresetsManagerScreen(onBack = { route = null })
            // `save-preset`: Build loaded from "Italian summer", then the Save sheet.
            var showSave by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (screen == "save-preset") {
                    model.snapshot.presets.presets.firstOrNull { it.id == "italian-summer" }?.let { p ->
                        model.updateBuild { BuildState.from(p.query, model.allStyles) }
                    }
                    showSave = true
                }
            }
            if (showSave) SavePresetSheet(presetID = null, onDismiss = { showSave = false }, initialName = "Italian summer")
        }
        null -> LibraryOverview(onRoute = { route = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryOverview(onRoute: (LibraryRoute) -> Unit) {
    val model = rememberModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = CrateTheme.colors
    val screen = DebugLaunch.screen

    var search by rememberSaveable { mutableStateOf(if (screen == "library-search") "quantic" else "") }
    var detail by remember { mutableStateOf<String?>(null) }
    var showToken by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var reloading by remember { mutableStateOf(false) }
    var spotifyConnected by remember { mutableStateOf(model.spotify.isConnected) }
    var spotifyError by remember { mutableStateOf<String?>(null) }
    var publicPlaylists by remember {
        mutableStateOf(
            if (screen == "library-hint") listOf(PublicPlaylist(model.snapshot.weekly.playlistID ?: "", "Crate Daily")) else emptyList(),
        )
    }
    var privacyHidden by remember { mutableStateOf(false) }
    var privacyCheck by remember { mutableIntStateOf(0) }
    var debugPrivacy by remember {
        mutableStateOf<Boolean?>(if (screen == "library-hint") true else if (screen?.startsWith("library") == true) false else null)
    }
    val showsPrivacyHint = debugPrivacy ?: (!privacyHidden && publicPlaylists.isNotEmpty())

    /** Asks Spotify whether Now and Crate Daily are public. Demo mode, no login, errors and offline: no card. */
    LaunchedEffect(privacyCheck) {
        if (debugPrivacy != null) return@LaunchedEffect
        if (model.isDemo || !model.spotify.isConnected) { publicPlaylists = emptyList(); return@LaunchedEffect }
        val mixID = model.snapshot.weekly.playlistID ?: model.snapshot.state.weeklyPlaylistID
        val candidates = listOf(model.nowPlaylistID to "Now", mixID to "Crate Daily")
            .mapNotNull { (id, name) -> id?.takeIf { it.isNotEmpty() }?.let { PublicPlaylist(it, name) } }
        val found = candidates.filter { p -> runCatching { model.spotify.client.playlist(p.id).isPublic == true }.getOrDefault(false) }
        publicPlaylists = found
        privacyHidden = false
    }

    fun connectSpotify() {
        scope.launch {
            try {
                model.spotify.connect(context)
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                spotifyError = "Couldn't connect to Spotify. Try again."
            }
            spotifyConnected = model.spotify.isConnected
            privacyCheck++
        }
    }

    val dismissPrivacy = { privacyHidden = true; debugPrivacy = null }
    val focus = LocalFocusManager.current
    BackHandler(enabled = search.isNotEmpty()) { search = ""; focus.clearFocus() }

    Column(Modifier.fillMaxSize().background(c.bgBase)) {
        LargeTitleHeader("Library")
        SearchField(search, { search = it }, Modifier.padding(horizontal = Space.screenMargin, vertical = Space.s))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = Space.screenMargin).padding(top = Space.s, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
        ) {
            if (search.isBlank()) {
                AnimatedVisibility(showsPrivacyHint) {
                    PrivacyCard(publicPlaylists, onDismiss = dismissPrivacy, onOpen = {
                        SpotifyOpen.openPlaylist(context, publicPlaylists.firstOrNull()?.id?.ifEmpty { null } ?: model.nowPlaylistID)
                    })
                }
                model.snapshot.health.problems.forEach { HealthProblemCard(it) }
                StatusCard()
                SourcesSection(spotifyConnected)
                Group("Your music") {
                    NavRow("Vibes", model.snapshot.vibes.vibes.size.toString()) { onRoute(LibraryRoute.VIBES) }
                    RowSeparator(Modifier.padding(start = Space.l))
                    NavRow("Presets", model.snapshot.presets.presets.size.toString()) { onRoute(LibraryRoute.PRESETS) }
                }
                Group("Settings") {
                    ValueRow("GitHub token", if (model.isDemo) "Demo data" else "Replace") { showToken = true }
                    RowSeparator(Modifier.padding(start = Space.l))
                    if (spotifyConnected) {
                        ActionRow("Disconnect Spotify", destructive = true) { confirmDisconnect = true }
                        RowSeparator(Modifier.padding(start = Space.l))
                        ActionRow("Reconnect Spotify") { connectSpotify() }
                    } else {
                        ActionRow("Connect Spotify") { connectSpotify() }
                    }
                    RowSeparator(Modifier.padding(start = Space.l))
                    ActionRow("Reload data", enabled = !reloading, trailing = {
                        if (reloading) CircularProgressIndicator(Modifier.size(18.dp), color = c.textSecondary, strokeWidth = 2.dp)
                    }) {
                        reloading = true
                        scope.launch { model.reload(); reloading = false; privacyCheck++ }
                    }
                }
            } else {
                SearchResults(search) { detail = it }
            }
        }
    }

    detail?.let { id -> TrackDetailSheet(trackID = id, onDismiss = { detail = null }) }
    if (showToken) GitHubTokenSheet(onDismiss = { showToken = false })
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Spotify?") },
            text = { Text("Send and likes stop working until you connect again. Your tags and playlists stay safe.") },
            confirmButton = {
                TextButton({
                    confirmDisconnect = false
                    model.spotify.disconnect()
                    spotifyConnected = model.spotify.isConnected
                }) { Text("Disconnect", color = c.error) }
            },
            dismissButton = { TextButton({ confirmDisconnect = false }) { Text("Cancel") } },
            containerColor = c.bgSheet,
        )
    }
    spotifyError?.let { msg ->
        AlertDialog(
            onDismissRequest = { spotifyError = null },
            title = { Text("Spotify") }, text = { Text(msg) },
            confirmButton = { TextButton({ spotifyError = null }) { Text("OK") } },
            containerColor = c.bgSheet,
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = CrateTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = Size.minTarget).background(c.fillControl, RoundedCornerShape(Radius.control))
            .padding(start = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = c.textSecondary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Search title or artist", style = CrateType.body, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = CrateType.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search title or artist" },
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(Size.minTarget).clearAndSetSemantics { contentDescription = "Clear search" }
                    .clickable(role = Role.Button) { onChange("") },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Cancel, null, Modifier.size(18.dp), tint = c.textTertiary) }
        }
    }
}

@Composable
private fun PrivacyCard(list: List<PublicPlaylist>, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val c = CrateTheme.colors
    Column(Modifier.fillMaxWidth().crateCard().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(Icons.Filled.Lock, null, Modifier.size(16.dp), tint = c.textPrimary)
            Text("Make Now and Crate Daily private", style = CrateType.headline, color = c.textPrimary, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(Size.minTarget).padding(start = Space.s).clearAndSetSemantics { contentDescription = "Dismiss" }
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, null, Modifier.size(16.dp), tint = c.textSecondary) }
        }
        Text("${PublicPlaylist.sentence(list)} In Spotify: ⋯ → Make private.", style = CrateType.secondary, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            CardButton("Open Spotify", onOpen)
            CardButton("Done", onDismiss, prominent = false)
        }
    }
}

@Composable
private fun StatusCard() {
    val model = rememberModel()
    val c = CrateTheme.colors
    val total = model.index.tracks.size
    val tagged = model.index.taggedCount
    val notAnalysable = model.index.tracks.values.count { it.status == TrackStatus.noPreview || it.status == TrackStatus.error }
    val health = model.snapshot.health
    val level = health.effectiveLevel
    Column(Modifier.fillMaxWidth().crateCard().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(
            Modifier.clearAndSetSemantics { contentDescription = "${LibraryText.number(total)} tracks" },
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            CountText(total, style = CrateType.numeralLarge, color = c.textPrimary)
            Text("tracks", style = CrateType.secondary, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.fillControlActive).clearAndSetSemantics {}) {
            Box(Modifier.fillMaxWidth(if (total == 0) 0f else tagged.toFloat() / total).height(6.dp).clip(CircleShape).background(c.textPrimary))
        }
        Row {
            Text("${LibraryText.number(tagged)} tagged", style = CrateType.footnote, color = c.textSecondary, modifier = Modifier.weight(1f))
            if (notAnalysable > 0) Text("$notAnalysable not analysable", style = CrateType.footnote, color = c.textSecondary)
        }
        Text(syncLine(model).uppercase(), style = CrateType.panelLabel, color = if (level == HealthLevel.broken) c.error else c.textSecondary)
        if (health.problems.isEmpty()) {
            HorizontalDivider(color = c.separator, thickness = 0.5.dp)
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                val ok = level == HealthLevel.healthy
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, Modifier.size(18.dp), tint = if (ok) c.success else c.warning)
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    Text(if (ok) "All good" else "Needs a look", style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
                    Text(healthDetail(model), style = CrateType.footnote, color = c.textSecondary)
                }
            }
        }
    }
}

private fun syncLine(model: com.overmap.crate.app.AppModel): String {
    val date = LibraryText.parseDate(model.snapshot.state.lastSync)
        ?: LibraryText.parseDate(model.snapshot.health.lastNightlySuccess) ?: model.lastSync ?: return "Not synced yet"
    var line = "Synced ${LibraryText.relativeDay(date)}"
    val days = LibraryText.daysAgo(date)
    if (days >= 2) line += " · $days days ago"
    if (model.snapshot.fromCache) line += " · offline copy"
    return line
}

private fun healthDetail(model: com.overmap.crate.app.AppModel): String {
    val parts = mutableListOf<String>()
    LibraryText.parseDate(model.snapshot.health.lastNightlySuccess)?.let { parts += "Last nightly run ${LibraryText.relativeDay(it)}" }
    for (cred in model.snapshot.health.credentials) cred.daysLeft?.let { parts += "${cred.name} expires in $it days" }
    if (parts.isEmpty()) parts += "No problems reported"
    return parts.joinToString(" · ")
}

/** One active problem from `health.json`, with its runbook step. */
@Composable
private fun HealthProblemCard(problem: HealthProblem) {
    val c = CrateTheme.colors
    val context = LocalContext.current
    val broken = problem.level == HealthLevel.broken
    Column(
        Modifier.fillMaxWidth().background(if (broken) c.errorFill else c.warningFill, RoundedCornerShape(Radius.card)).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(if (broken) Icons.Filled.Report else Icons.Filled.Warning, null, Modifier.size(18.dp), tint = if (broken) c.error else c.warning)
            Text(problem.message, style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
        }
        val anchor = problem.runbook
        val url = LibraryText.runbookURL(anchor)
        if (anchor != null && url != null) {
            Box(Modifier.heightIn(min = Size.minTarget).clickable(role = Role.Button) { openURL(context, url) }, contentAlignment = Alignment.CenterStart) {
                Text(
                    buildAnnotatedString {
                        append("Runbook: ")
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(LibraryText.runbookTitle(anchor)) }
                    },
                    style = CrateType.footnote, color = c.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun SourcesSection(spotifyConnected: Boolean) {
    val model = rememberModel()
    val n = LibraryText::number
    val tracks = model.index.tracks.values
    fun sourceCount(key: String) = tracks.count { !it.consensus?.bySource?.get(key).isNullOrEmpty() }
    val lastfm = tracks.count { !it.tags.isNullOrEmpty() }
    val discogs = sourceCount("discogs")
    val beatport = sourceCount("beatport")
    val total = model.index.tracks.size
    Column {
        Group("Sources") {
            SourceRow("Spotify", spotifyConnected, when {
                model.isDemo -> "${n(total)} tracks · demo"
                spotifyConnected -> "${n(total)} tracks"
                else -> "Not connected"
            })
            RowSeparator(Modifier.padding(start = Space.l))
            SourceRow("Audio model", model.index.taggedCount > 0, "${n(model.index.taggedCount)} tagged")
            RowSeparator(Modifier.padding(start = Space.l))
            SourceRow("Discogs", discogs > 0, "${n(discogs)} matched")
            RowSeparator(Modifier.padding(start = Space.l))
            SourceRow("Last.fm", lastfm > 0, "${n(lastfm)} with tags")
            RowSeparator(Modifier.padding(start = Space.l))
            SourceRow("Beatport", beatport > 0, "${n(beatport)} entered by hand")
        }
        Text(
            "Beatport genres are entered outside the app.", style = CrateType.footnote, color = CrateTheme.colors.textTertiary,
            modifier = Modifier.padding(start = Space.l, top = Space.s),
        )
    }
}

/** A titled inset group: panel label, then a card of rows. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        PanelLabel(title, Modifier.padding(start = Space.l))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).crateCard(), content = content)
    }
}

@Composable
private fun SourceRow(name: String, ok: Boolean, value: String) {
    val c = CrateTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTarget).padding(horizontal = Space.l)
            .semantics(mergeDescendants = true) { if (!ok) stateDescription = "not available" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Box(Modifier.size(8.dp).background(if (ok) c.success else c.textTertiary, CircleShape))
        Text(name, style = CrateType.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = CrateType.secondary, color = c.textSecondary, textAlign = TextAlign.End)
    }
}

@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
    val c = CrateTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable(role = Role.Button, onClick = onClick)
            .padding(start = Space.l, end = Space.m).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(title, style = CrateType.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = CrateType.body, color = c.textSecondary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = c.textTertiary)
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    val c = CrateTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = CrateType.body, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = CrateType.body, color = c.textSecondary)
    }
}

@Composable
private fun ActionRow(
    title: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    val c = CrateTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = CrateType.body, color = if (destructive) c.error else c.textPrimary, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SearchResults(search: String, onOpen: (String) -> Unit) {
    val model = rememberModel()
    val c = CrateTheme.colors
    val hits = remember(search, model.index) {
        val q = LibraryText.fold(search.trim())
        if (q.isEmpty()) emptyList() else {
            val idx = model.index
            idx.ids.filter { id ->
                val t = idx.tracks[id] ?: return@filter false
                LibraryText.fold(t.title ?: "").contains(q) || t.artists.orEmpty().any { LibraryText.fold(it).contains(q) }
            }.sortedWith(
                compareByDescending<String> { LibraryText.fold(idx.tracks[it]?.title ?: "").startsWith(q) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { idx.tracks[it]?.title ?: "" },
            ).take(100)
        }
    }
    if (hits.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(top = Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(Icons.Filled.Search, null, Modifier.size(44.dp), tint = c.textSecondary)
            Text("No results for “${search.trim()}”", style = CrateType.sheetTitle, color = c.textPrimary, textAlign = TextAlign.Center)
            Text("Check the spelling or try a new search.", style = CrateType.secondary, color = c.textSecondary, textAlign = TextAlign.Center)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        PanelLabel("${if (hits.size == 100) "100+" else hits.size.toString()} tracks", Modifier.padding(start = Space.l))
        Column(Modifier.fillMaxWidth().crateCard()) {
            hits.forEachIndexed { i, id ->
                val t = model.index.tracks[id] ?: return@forEachIndexed
                val eff = model.index.effective[id]
                val styleName = eff?.primaryStyle?.let { StyleLabel(it, model.snapshot.taxonomy.aliases).styleDisplay }
                TrackRow(
                    title = t.title ?: "Unknown track",
                    artist = t.artists.orEmpty().joinToString(", "),
                    coverURL = t.cover,
                    coverTint = familyColor(LibraryText.family(eff?.family), dark = c.isDark),
                    tagLine = { CompactTagLine(track = t, styleName = styleName) },
                    modifier = Modifier.clickable(role = Role.Button) { onOpen(id) }.padding(horizontal = Space.l),
                )
                if (i < hits.lastIndex) RowSeparator(Modifier.padding(start = Space.l + Size.coverRow + Space.m))
            }
        }
    }
}

/** Replace the GitHub token: paste, save to encrypted storage, validate by loading the library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitHubTokenSheet(onDismiss: () -> Unit) {
    val model = rememberModel()
    val c = CrateTheme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var token by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<TokenCheck>(TokenCheck.Idle) }
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    fun save() {
        state = TokenCheck.Checking
        scope.launch {
            try {
                model.saveGitHubToken(token)
                state = TokenCheck.Ok(model.validateGitHub())
                model.reload()
                close()
            } catch (e: Exception) {
                state = TokenCheck.Failed(TokenCheck.message(e))
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bgSheet, dragHandle = null) {
        SheetTopBar(
            "GitHub token", onCancel = close, confirmTitle = "Save",
            confirmEnabled = token.isNotBlank() && state != TokenCheck.Checking, onConfirm = ::save,
        )
        Column(
            Modifier.padding(Space.screenMargin).padding(bottom = Space.xl).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
        ) {
            Text(
                "Paste a fine-grained token with read and write access to the crate-data repo only. It's stored in encrypted storage on this phone.",
                style = CrateType.secondary, color = c.textSecondary,
            )
            GitHubTokenField(token, { token = it; if (state != TokenCheck.Checking) state = TokenCheck.Idle })
            TokenCheckLine(state)
        }
    }
}
