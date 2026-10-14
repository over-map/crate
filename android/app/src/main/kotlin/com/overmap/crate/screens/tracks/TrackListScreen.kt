package com.overmap.crate.screens.tracks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.overmap.crate.app.AppModel
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.PrimaryButton
import com.overmap.crate.ui.components.PrimaryButtonState
import com.overmap.crate.ui.components.TrackRow
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.familyColor
import kotlinx.coroutines.launch
import java.time.Instant

/** Sort orders of [TrackListScreen]. */
enum class TrackListSort(val label: String, val icon: ImageVector) {
    ORIGINAL("Default", Icons.AutoMirrored.Filled.List),
    LIKED("Recently liked", Icons.Filled.FavoriteBorder),
    BPM("BPM", Icons.Filled.Speed),
    ENERGY("Energy", Icons.Filled.Bolt),
    TITLE("Title", Icons.AutoMirrored.Filled.TextSnippet),
}

/**
 * Track list (design 6p): the given tracks, sortable; a tap opens the track detail sheet. With [showsSend] (pushed
 * from Build) the Send button stays pinned at the bottom (uses `model.send()` / `sendState`, the current Build query).
 *
 * @param subtitle Line under the title, e.g. "Happy · italian". "Now is shuffled when sent" is added with [showsSend].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    title: String,
    trackIDs: List<String>,
    onBack: () -> Unit,
    subtitle: String? = null,
    showsSend: Boolean = false,
) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    var sort by rememberSaveable { mutableStateOf(TrackListSort.ORIGINAL) }
    var sortMenu by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    BackHandler(onBack = onBack)

    val infos = remember(trackIDs, model.index, model.snapshot.taxonomy, sort) {
        sorted(trackIDs.mapNotNull { TrackInfo.of(it, model) }, sort)
    }
    val header = listOfNotNull(subtitle, if (showsSend) "Now is shuffled when sent" else null).filter { it.isNotEmpty() }
        .joinToString(" · ").ifEmpty { null }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.bgBase,
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    Box {
                        TextButton(
                            onClick = { sortMenu = true },
                            modifier = Modifier.semantics { contentDescription = "Sort, ${sort.label}" },
                        ) { Text("Sort", style = CrateType.body, color = colors.textPrimary) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, containerColor = colors.bgCard) {
                            TrackListSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    leadingIcon = { Icon(s.icon, null) },
                                    trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                                    onClick = { sort = s; sortMenu = false },
                                )
                            }
                            if (showsSend) {
                                HorizontalDivider(color = colors.separator)
                                Text(
                                    "Sorting doesn't change Now: it's shuffled when sent.",
                                    style = CrateType.footnote, color = colors.textSecondary,
                                    modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bgBase, scrolledContainerColor = colors.bgBase,
                    titleContentColor = colors.textPrimary, navigationIconContentColor = colors.textPrimary,
                    actionIconContentColor = colors.textPrimary,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = { if (showsSend) SendBar(model) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.screenMargin, end = Space.screenMargin,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Space.l,
            ),
        ) {
            if (header != null) {
                item(key = "header") {
                    Text(header, style = CrateType.footnote, color = colors.textSecondary, modifier = Modifier.padding(bottom = Space.xs))
                }
            }
            items(infos, key = { it.id }) { info ->
                Column {
                    TrackRow(
                        title = info.title, artist = info.artist, coverURL = info.coverURL,
                        coverTint = familyColor(info.family, dark = colors.isDark),
                        tagLine = { info.TagLine(model.tracksVibes(info.id).map { it.vibe.name }) },
                        modifier = Modifier.clickable(onClickLabel = "Open the track") { selected = info.id },
                    )
                    HorizontalDivider(Modifier.padding(start = Size.coverRow + Space.m), color = colors.separator)
                }
            }
            if (infos.isEmpty()) item(key = "empty") { EmptyState("No tracks") }
        }
    }
    selected?.let { id -> TrackDetailSheet(id, onDismiss = { selected = null }, startsExpanded = false) }
}

private fun sorted(infos: List<TrackInfo>, sort: TrackListSort): List<TrackInfo> = when (sort) {
    TrackListSort.ORIGINAL -> infos
    TrackListSort.LIKED -> infos.sortedByDescending { it.likedDate ?: Instant.MIN }
    TrackListSort.BPM -> infos.sortedWith(compareBy<TrackInfo> { it.bpm == null }.thenBy { it.bpm ?: 0.0 }.thenBy { it.title })
    TrackListSort.ENERGY -> infos.sortedWith(compareByDescending<TrackInfo> { it.energy ?: 0 }.thenBy { it.bpm ?: 0.0 })
    TrackListSort.TITLE -> infos.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}

@Composable
private fun SendBar(model: AppModel) {
    val colors = CrateTheme.colors
    val state = when (val s = model.sendState) {
        AppModel.SendState.Idle ->
            if (model.sendCount == 0) PrimaryButtonState.Disabled("No tracks to send") else PrimaryButtonState.Idle("Send ${model.sendCount} to Now")
        is AppModel.SendState.Sending -> PrimaryButtonState.Sending(s.done.toDouble() / maxOf(s.total, 1), "${s.done}/${s.total}")
        is AppModel.SendState.Success -> PrimaryButtonState.Success("Sent ${s.count} tracks to Now", "Listen on Spotify")
        is AppModel.SendState.Error -> PrimaryButtonState.Error(s.message, "Retry")
    }
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to colors.bgBase.copy(alpha = 0f), 0.35f to colors.bgBase))
            .navigationBarsPadding()
            .padding(start = Space.screenMargin, end = Space.screenMargin, top = Space.l, bottom = Space.m),
    ) {
        PrimaryButton(
            state,
            onClick = {
                if (model.sendState is AppModel.SendState.Success) model.openNow() else model.scope.launch { model.send() }
            },
            secondaryTitle = "Send again",
            secondaryAction = { model.scope.launch { model.send() } },
            liftOffCovers = model.lastSentCovers,
        )
    }
}
