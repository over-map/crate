package com.overmap.crate.screens.build

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.AppModel
import com.overmap.crate.app.BuildState
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.core.Query
import com.overmap.crate.screens.library.SavePresetSheet
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.screens.tracks.TrackListScreen
import com.overmap.crate.screens.vibes.VibeEditorSheet
import com.overmap.crate.screens.vibes.VibesManagerScreen
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.crateIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sheets presented from Build. */
private sealed interface BuildSheet {
    data class Picker(val focusFamily: String?) : BuildSheet
    data object Key : BuildSheet
    data class VibeEditor(val id: String?) : BuildSheet
    data class SavePreset(val id: String?) : BuildSheet
}

/** Screens pushed from Build. */
private sealed interface BuildRoute {
    data class Tracks(val title: String, val trackIDs: List<String>) : BuildRoute
    data object VibesManager : BuildRoute
}

/** Which BPM numeral is being typed. */
internal enum class BpmBound { MIN, MAX }

/** Debug `--es screen …` hooks are applied once per process. */
private var debugApplied = false

/**
 * Build tab (design 6b–6f, 2a–2e): the port of iOS `BuildView`.
 *
 * Order: preset pills, sticky result card, genre & style, vibe, tags, BPM, energy, key + vocals, limit.
 * The Send button is pinned above the tab bar with a fade to `bgBase`. While the tag field has focus the preset row,
 * the lower sections and the Send bar hide so the suggestions stay above the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildScreen() {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    var sheet by remember { mutableStateOf<BuildSheet?>(null) }
    var route by remember { mutableStateOf<BuildRoute?>(null) }
    var loadedPresetID by rememberSaveable { mutableStateOf<String?>(null) }
    var tagText by rememberSaveable { mutableStateOf("") }
    var tagFocused by remember { mutableStateOf(false) }
    val tagFocus = remember { FocusRequester() }
    var bpmEditing by remember { mutableStateOf<BpmBound?>(null) }
    var bpmText by remember { mutableStateOf("") }
    val bpmFocus = remember { mapOf(BpmBound.MIN to FocusRequester(), BpmBound.MAX to FocusRequester()) }

    // Debug hooks (`--es screen picker|key|tags|filters|empty|sending|sent`, `--ez bottom true`).
    LaunchedEffect(Unit) {
        if (debugApplied) return@LaunchedEffect
        debugApplied = true
        applyDebugScreen(
            model,
            openSheet = { sheet = it },
            setTagText = { tagText = it },
            focusTags = { runCatching { tagFocus.requestFocus() } },
            setLoaded = { loadedPresetID = it },
        )
        if (DebugLaunch.bottom) {
            delay(300)
            listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    // Pushed screens replace Build inside the tab (the tab bar stays), like a NavigationStack push.
    when (val r = route) {
        is BuildRoute.Tracks -> {
            BackHandler { route = null }
            TrackListScreen(title = r.title, trackIDs = r.trackIDs, onBack = { route = null })
            return
        }
        BuildRoute.VibesManager -> {
            BackHandler { route = null }
            VibesManagerScreen(onBack = { route = null })
            return
        }
        null -> Unit
    }

    // Keyboard: Android keeps focus when the IME is dismissed with Back; drop it so the hidden sections come back.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) {
            focusManager.clearFocus()
            bpmEditing = null
        }
        imeWasVisible = imeVisible
    }
    // The part of the keyboard that overlaps this screen (the tab bar sits under the keyboard).
    val density = LocalDensity.current
    val view = LocalView.current
    var bottomGapPx by remember { mutableIntStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeOverlap = with(density) { (imeBottom - bottomGapPx).coerceAtLeast(0).toDp() }

    // Focusing the tag field scrolls it up under the sticky result card (again once the keyboard is up).
    var headerHeightPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(tagFocused, imeVisible) {
        if (tagFocused) {
            delay(150)
            val index = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "tags" }?.index ?: tagsIndex(model)
            listState.animateScrollToItem(index, scrollOffset = -headerHeightPx)
        }
    }

    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val build = model.build
    val resetDisabled = build.isEmpty && build.limit == Query.defaultLimit
    val showPresets = model.snapshot.presets.presets.isNotEmpty() && !tagFocused

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bgBase)
            .onGloballyPositioned { c ->
                val bottom = c.positionInWindow().y + c.size.height
                bottomGapPx = (view.rootView.height - bottom).toInt().coerceAtLeast(0)
            },
    ) {
        // Toolbar: an inline title once the large title has scrolled away; Reset and Save as preset on the right.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(start = Space.m)) {
                if (collapsed) Text("Build", style = CrateType.headline, color = colors.textPrimary)
            }
            TextButton(
                onClick = {
                    model.updateBuild { BuildState() }
                    loadedPresetID = null
                    tagText = ""
                },
                enabled = !resetDisabled,
            ) {
                Text("Reset", style = CrateType.body, color = if (resetDisabled) colors.textTertiary else colors.textSecondary)
            }
            IconButton(
                onClick = { sheet = BuildSheet.SavePreset(null) },
                enabled = !build.isEmpty,
                modifier = Modifier.semantics { contentDescription = "Save as preset" },
            ) {
                Icon(
                    crateIcon(if (build.isEmpty) "bookmark" else "bookmark.fill"), null,
                    tint = if (build.isEmpty) colors.textTertiary else colors.textPrimary,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = imeOverlap)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.screenMargin, end = Space.screenMargin,
                    bottom = if (tagFocused || bpmEditing != null) Space.l else SEND_BAR_RESERVE,
                ),
            ) {
                item(key = "title") {
                    Text(
                        "Build", style = CrateType.screenTitle, color = colors.textPrimary,
                        modifier = Modifier.padding(bottom = Space.s).semantics { heading() },
                    )
                }
                if (showPresets) {
                    item(key = "presets") {
                        BuildPresetRow(
                            loadedPresetID = loadedPresetID,
                            onLoadedChange = { loadedPresetID = it },
                            onEdit = { sheet = BuildSheet.SavePreset(it) },
                            modifier = Modifier.padding(bottom = Space.s),
                        )
                    }
                }
                stickyHeader(key = "result") {
                    Box(
                        Modifier.fillMaxWidth().background(colors.bgBase)
                            .onGloballyPositioned { headerHeightPx = it.size.height }
                            .padding(vertical = Space.xs),
                    ) {
                        BuildResultCard(onOpen = { title, ids -> route = BuildRoute.Tracks(title, ids) })
                    }
                }
                if (model.matchCount == 0 && !build.isEmpty) {
                    item(key = "empty") { Section { BuildEmptyState() } }
                }
                item(key = "genre") { Section { BuildGenreSection(onOpenPicker = { sheet = BuildSheet.Picker(it) }) } }
                item(key = "vibe") {
                    Section {
                        BuildVibeSection(
                            onEdit = { sheet = BuildSheet.VibeEditor(it) },
                            onManage = { route = BuildRoute.VibesManager },
                        )
                    }
                }
                item(key = "tags") {
                    Section {
                        BuildTagSection(
                            text = tagText,
                            onTextChange = { tagText = it },
                            focused = tagFocused,
                            onFocusedChange = { tagFocused = it },
                            focusRequester = tagFocus,
                        )
                    }
                }
                if (tagFocused) {
                    // Room so the tag field can scroll up to the result card even with the lower sections hidden.
                    item(key = "tagRoom") { Spacer(Modifier.fillParentMaxHeight(0.8f)) }
                } else {
                    item(key = "bpm") {
                        Section {
                            BuildBPMSection(
                                editing = bpmEditing,
                                text = bpmText,
                                onTextChange = { bpmText = it },
                                onStartEditing = { b ->
                                    bpmText = (if (b == BpmBound.MIN) model.build.bpm.first else model.build.bpm.last).toString()
                                    bpmEditing = b
                                },
                                onStopEditing = { b -> if (bpmEditing == b) bpmEditing = null },
                                focusRequesters = bpmFocus,
                            )
                        }
                    }
                    item(key = "energy") { Section { BuildEnergySection() } }
                    item(key = "keyvocal") { Section { BuildKeyVocalSection(onOpenKey = { sheet = BuildSheet.Key }) } }
                    item(key = "limit") { Section { BuildLimitSection() } }
                }
            }

            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                val editing = bpmEditing
                when {
                    tagFocused -> Unit
                    editing != null -> BpmKeyboardBar(
                        editing = editing,
                        onSwitch = { b ->
                            bpmText = (if (b == BpmBound.MIN) model.build.bpm.first else model.build.bpm.last).toString()
                            bpmEditing = b
                            scope.launch { runCatching { bpmFocus.getValue(b).requestFocus() } }
                        },
                        onStep = { d -> bpmText = stepBpm(model, editing, d) },
                        onDone = { bpmEditing = null; focusManager.clearFocus() },
                    )
                    else -> BuildSendBar()
                }
            }
        }
    }

    when (val s = sheet) {
        is BuildSheet.Picker -> GenrePickerSheet(onDismiss = { sheet = null }, focusFamily = s.focusFamily)
        BuildSheet.Key -> KeyPickerSheet(onDismiss = { sheet = null })
        is BuildSheet.VibeEditor -> VibeEditorSheet(vibeID = s.id, seedTrackID = null, onDismiss = { sheet = null })
        is BuildSheet.SavePreset -> SavePresetSheet(presetID = s.id, onDismiss = { sheet = null })
        null -> Unit
    }
}

/** Room under the list for the pinned Send bar (button, message line and fade). */
private val SEND_BAR_RESERVE = 140.dp

/** Index of the tags item while the tag field has focus: title, result, (empty), genre, vibe, tags. */
private fun tagsIndex(model: AppModel): Int = if (model.matchCount == 0 && !model.build.isEmpty) 5 else 4

/** One Build section with the section gap above it. */
@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.sectionGap - Space.xs, bottom = Space.xs)) { content() }
}

/** Steps the BPM bound being typed by [delta] (the keyboard bar's − / +); returns the new text. */
private fun stepBpm(model: AppModel, bound: BpmBound, delta: Int): String {
    val r = model.build.bpm
    val b = BuildState.BPM_ANY
    return when (bound) {
        BpmBound.MIN -> {
            val v = (r.first + delta).coerceIn(b.first, r.last)
            model.updateBuild { it.copy(bpm = v..r.last) }
            v.toString()
        }
        BpmBound.MAX -> {
            val v = (r.last + delta).coerceIn(r.first, b.last)
            model.updateBuild { it.copy(bpm = r.first..v) }
            v.toString()
        }
    }
}

private suspend fun applyDebugScreen(
    model: AppModel,
    openSheet: (BuildSheet) -> Unit,
    setTagText: (String) -> Unit,
    focusTags: () -> Unit,
    setLoaded: (String) -> Unit,
) {
    when (DebugLaunch.screen) {
        "picker" -> {
            val fam = model.index.families.firstOrNull()
            if (fam != null) {
                val all = model.allStyles[fam.family] ?: emptySet()
                val drop = fam.styles.drop(3).firstOrNull()?.first
                model.updateBuild { it.copy(families = mapOf(fam.family to (all - setOfNotNull(drop)))) }
            }
            openSheet(BuildSheet.Picker(fam?.family))
        }
        "key" -> {
            model.updateBuild { it.copy(key = "8A", compatible = true) }
            openSheet(BuildSheet.Key)
        }
        "tags" -> {
            val vibes = listOf("dark", "hypnotic").filter { model.snapshot.vibes.vibes.containsKey(it) }
            model.updateBuild { it.copy(vibes = vibes) }
            setTagText("dub")
            delay(600)
            focusTags()
        }
        "filters" -> model.updateBuild { it.copy(vibes = listOf("happy"), tags = listOf("italian")) }
        "empty" -> {
            // Narrow "italian + Happy" step by step until nothing matches (an impossible combination).
            model.updateBuild { BuildState(vibes = listOf("happy"), tags = listOf("italian")) }
            val steps: List<(BuildState) -> BuildState> = listOf(
                { it.copy(bpm = 120..132) }, { it.copy(energy = 8..10) }, { it.copy(vibes = it.vibes + "romantic") },
                { it.copy(vocal = Query.Vocal.instrumental) }, { it.copy(key = "1B", compatible = false) },
            )
            for (step in steps) if (model.matchCount > 0) model.updateBuild(step)
        }
        "sending", "sent" -> {
            model.snapshot.presets.presets.firstOrNull()?.let { p ->
                model.updateBuild { BuildState.from(p.query, model.allStyles) }
                setLoaded(p.id)
            }
            if (DebugLaunch.screen == "sent") {
                model.send()
            } else {
                val total = model.sendCount
                model.sendState = AppModel.SendState.Sending(total * 2 / 5, total)
            }
        }
    }
}
