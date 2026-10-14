package com.overmap.crate.screens.tracks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.AppModel
import com.overmap.crate.core.Camelot
import com.overmap.crate.core.EffectiveTags
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.AddChip
import com.overmap.crate.ui.components.BPMText
import com.overmap.crate.ui.components.CamelotBadge
import com.overmap.crate.ui.components.CamelotBadgeStyle
import com.overmap.crate.ui.components.CamelotWheel
import com.overmap.crate.ui.components.ChipFlow
import com.overmap.crate.ui.components.FamilyDot
import com.overmap.crate.ui.components.HapticOnChange
import com.overmap.crate.ui.components.PanelLabel
import com.overmap.crate.ui.components.StyleChip
import com.overmap.crate.ui.components.StyleChipVariant
import com.overmap.crate.ui.components.VibeChip
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.energyColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Edit track (design 6o, 2k): genre override, styles, vibes, BPM, key, energy, vocals. Edits a draft; Save writes
 * `overrides.json` through `model.editOverride` and the vibe seeds / excludes through `model.editVibes`. Save stays
 * disabled until something changed; swiping the sheet away or pressing back with changes asks to discard them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTrackSheet(trackID: String, onDismiss: () -> Unit) {
    val model = rememberModel()
    val scope = rememberCoroutineScope()
    val original = remember(trackID) { TrackOverrideDraft.from(model.index.overrides[trackID]) }
    var draft by remember(trackID) { mutableStateOf(original) }
    val vibesInitial = remember(trackID) { model.tracksVibes(trackID).map { it.id }.toSet() }
    var vibesIn by remember(trackID) { mutableStateOf(vibesInitial) }
    val changed = draft != original || vibesIn != vibesInitial
    val changedNow by rememberUpdatedState(changed)
    var allowClose by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { v ->
            if (v == SheetValue.Hidden && changedNow && !allowClose) { askDiscard = true; false } else true
        },
    )
    val close: () -> Unit = {
        allowClose = true
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = { if (changedNow && !allowClose) askDiscard = true else onDismiss() },
        sheetState = state,
        containerColor = CrateTheme.colors.bgSheet,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            SheetTopBar(
                "Cancel", close, "Save",
                onTrailing = {
                    save(model, trackID, draft, draft != original, vibesIn - vibesInitial, vibesInitial - vibesIn)
                    close()
                },
                title = "Edit track", trailingEnabled = changed, trailingBold = true,
            )
            val base = TrackInfo.of(trackID, model)
            if (base == null) {
                EmptyState("Track not in library")
            } else {
                EditForm(
                    model, base, draft, { draft = it }, vibesIn, { vibesIn = it }, vibesIn != vibesInitial,
                    onRevert = {
                        model.scope.launch { model.editOverride(trackID) { TrackOverrideDraft().apply(it) } }
                        close()
                    },
                )
            }
        }
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("Your edits to this track aren't saved yet.") },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; close() }) { Text("Discard changes", color = CrateTheme.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) { Text("Keep editing", color = CrateTheme.colors.textPrimary) }
            },
            containerColor = CrateTheme.colors.bgCard,
        )
    }
}

/** Writes the tag edits and the vibe changes (tick = seed, untick = exclude) in the model's scope. */
private fun save(model: AppModel, id: String, draft: TrackOverrideDraft, tagsChanged: Boolean, added: Set<String>, removed: Set<String>) {
    model.scope.launch {
        if (tagsChanged) model.editOverride(id) { draft.apply(it) }
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            model.editVibes { file ->
                val vibes = file.vibes.toMutableMap()
                for (vid in added) {
                    val v = vibes[vid] ?: continue
                    vibes[vid] = v.copy(exclude = v.exclude - id, seeds = if (id in v.seeds) v.seeds else v.seeds + id)
                }
                for (vid in removed) {
                    val v = vibes[vid] ?: continue
                    vibes[vid] = v.copy(seeds = v.seeds - id, exclude = if (id in v.exclude) v.exclude else listOf(id) + v.exclude)
                }
                file.copy(vibes = vibes)
            }
        }
    }
}

/** The track as it would look with the draft applied. */
private fun preview(base: TrackInfo, draft: TrackOverrideDraft): TrackInfo {
    val o = draft.apply(base.override ?: com.overmap.crate.core.TrackOverride())
    return TrackInfo(base.id, base.track, if (o.isEmpty) null else o, base.taxonomy)
}

@Composable
private fun EditForm(
    model: AppModel,
    base: TrackInfo,
    draft: TrackOverrideDraft,
    setDraft: (TrackOverrideDraft) -> Unit,
    vibesIn: Set<String>,
    setVibes: (Set<String>) -> Unit,
    vibesEdited: Boolean,
    onRevert: () -> Unit,
) {
    val info = preview(base, draft)
    val scroll = rememberScrollState()
    var confirmRevert by remember { mutableStateOf(false) }
    if (TracksDebug.scrollToBottom) {
        LaunchedEffect(Unit) {
            snapshotFlow { scroll.maxValue }.first { it in 1 until Int.MAX_VALUE }
            repeat(4) { delay(300); scroll.scrollTo(scroll.maxValue) }
        }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = Space.screenMargin)
            .padding(top = Space.s, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
    ) {
        GenreSection(model, info, draft, setDraft)
        StylesSection(model, info, draft, setDraft)
        VibesSection(model, vibesIn, setVibes, vibesEdited)
        if (info.isAnalysed || draft.bpm != null) BpmSection(info, draft, setDraft)
        KeySection(info, draft, setDraft)
        EnergySection(info, draft, setDraft)
        VocalsSection(info, draft, setDraft)
        if (base.override != null || draft != TrackOverrideDraft()) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable(role = Role.Button) { confirmRevert = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("Revert all to automatic", style = CrateType.body, color = CrateTheme.colors.error)
            }
        }
    }
    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text("Revert all tags to automatic?") },
            text = { Text("Your genre, style, BPM, key, energy and vocal corrections for this track are removed.") },
            confirmButton = {
                TextButton(onClick = { confirmRevert = false; onRevert() }) { Text("Revert to automatic", color = CrateTheme.colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRevert = false }) { Text("Cancel", color = CrateTheme.colors.textPrimary) } },
            containerColor = CrateTheme.colors.bgCard,
        )
    }
}

// Genre

@Composable
private fun GenreSection(model: AppModel, info: TrackInfo, draft: TrackOverrideDraft, setDraft: (TrackOverrideDraft) -> Unit) {
    val colors = CrateTheme.colors
    var showAll by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        PanelLabel("Genre")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            StyleChip(style = info.familyName(info.family), family = info.family, edited = draft.family != null)
            sourcesLine(info)?.let {
                Text(it, style = CrateType.footnote, color = colors.textSecondary, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            if (draft.family != null) {
                TextButton(
                    onClick = { setDraft(draft.copy(family = null)) },
                    modifier = Modifier.semantics { contentDescription = "Reset genre to what the sources said" },
                ) { Text("Reset", style = CrateType.secondary, color = colors.textPrimary) }
            }
        }
        val all = allFamilies(info)
        val shown = if (showAll) all else shortFamilies(model, info, all)
        Column(Modifier.fillMaxWidth().crateCard().animateContentSize()) {
            shown.forEach { f ->
                val isSources = f == info.sourcesFamily
                val isCurrent = f == info.family
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { setDraft(draft.copy(family = if (isSources) null else f.data)) }
                        .heightIn(min = Size.minTarget + Space.xs).padding(horizontal = Space.m)
                        .clearAndSetSemantics {
                            contentDescription = info.familyName(f) + if (isSources) ", what the sources said" else ""
                            role = Role.RadioButton
                            selected = isCurrent
                        },
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FamilyDot(f)
                    Text(info.familyName(f), style = CrateType.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    if (isSources) Text("sources", style = CrateType.footnote, color = colors.textTertiary)
                    if (isCurrent) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = colors.textPrimary)
                }
                HorizontalDivider(color = colors.separator)
            }
            Row(
                Modifier.fillMaxWidth().clickable { showAll = !showAll }.heightIn(min = Size.minTarget).padding(horizontal = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (showAll) "Fewer genres" else "All ${all.size} genres", style = CrateType.footnote, color = colors.textSecondary)
                Icon(
                    if (showAll) Icons.Filled.ExpandLess else Icons.AutoMirrored.Filled.ArrowForwardIos, null,
                    Modifier.size(if (showAll) 16.dp else 11.dp), tint = colors.textSecondary,
                )
            }
        }
    }
}

private fun sourcesLine(info: TrackInfo): String? {
    val src = info.sourcesFamily ?: return null
    val name = info.familyName(src)
    val c = info.track.consensus
    val v = c?.votes
    val n = c?.sources
    return if (v != null && n != null && n > 0) "Sources said $name ($v of $n)" else "Sources said $name"
}

private fun allFamilies(info: TrackInfo): List<Family> {
    val ids = info.taxonomy.families.ifEmpty { Family.entries.map { it.data } }
    return ids.map { Family.of(it) }.filter { it != Family.NONE }.distinct()
}

/** Up to 5: what the sources said, the current pick, the families sources voted for, then the library's biggest. */
private fun shortFamilies(model: AppModel, info: TrackInfo, all: List<Family>): List<Family> {
    val votes = HashMap<String, Int>()
    for (fams in info.track.consensus?.bySource.orEmpty().values) for (f in fams) votes.merge(f, 1, Int::plus)
    val out = mutableListOf<Family>()
    fun add(f: Family?) { if (f != null && f != Family.NONE && f !in out && out.size < 5) out += f }
    add(info.sourcesFamily)
    add(info.family)
    votes.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).forEach { add(Family.of(it.key)) }
    model.index.families.forEach { add(Family.of(it.family)) }
    all.forEach { add(it) }
    return out
}

// Styles

@Composable
private fun StylesSection(model: AppModel, info: TrackInfo, draft: TrackOverrideDraft, setDraft: (TrackOverrideDraft) -> Unit) {
    val current = info.displayStyles
    val removed = draft.stylesRemove.filter { it !in current }
    var adding by remember { mutableStateOf(false) }
    fun remove(s: String) {
        val add = draft.stylesAdd - s
        val rem = if (info.track.auto?.styles?.containsKey(s) == true && s !in draft.stylesRemove) draft.stylesRemove + s else draft.stylesRemove
        setDraft(draft.copy(stylesAdd = add, stylesRemove = rem))
    }
    fun makePrimary(s: String) = setDraft(draft.copy(stylesRemove = draft.stylesRemove - s, stylesAdd = listOf(s) + (draft.stylesAdd - s)))
    fun addStyle(s: String) {
        val rem = draft.stylesRemove - s
        if (s in draft.stylesAdd) { setDraft(draft.copy(stylesRemove = rem)); return }
        val primary = info.effective.primaryStyle
        // The first added style becomes the primary; keep the current primary first when adding a secondary.
        val add = if (draft.stylesAdd.isEmpty() && primary != null && primary != s) listOf(primary, s) else draft.stylesAdd + s
        setDraft(draft.copy(stylesAdd = add, stylesRemove = rem))
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        PanelLabel("Styles")
        ChipFlow {
            current.forEachIndexed { i, s ->
                EditableStyleChip(
                    name = info.styleName(s), canonical = s, family = info.family, edited = info.isStyleEdited(s),
                    canMakeMain = i > 0, onMakeMain = { makePrimary(s) }, onRemove = { remove(s) },
                )
            }
            removed.forEach { s ->
                StyleChip(
                    style = info.styleName(s), canonical = s, family = info.family, variant = StyleChipVariant.Removed,
                    onUndo = { setDraft(draft.copy(stylesRemove = draft.stylesRemove - s)) },
                )
            }
            AddChip("Style", onClick = { adding = true })
        }
        if (current.size > 1) {
            Text(
                "The first style decides the genre match. Touch and hold a style to make it the main one.",
                style = CrateType.footnote, color = CrateTheme.colors.textTertiary,
            )
        }
    }
    if (adding) TrackStylePickerSheet(model, onPick = { addStyle(it) }, onDismiss = { adding = false })
}

/** A selected style chip with × and a long-press menu (Make main style / Remove), mirrored as TalkBack actions. */
@Composable
private fun EditableStyleChip(
    name: String, canonical: String, family: Family, edited: Boolean,
    canMakeMain: Boolean, onMakeMain: () -> Unit, onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clearAndSetSemantics {
                contentDescription = name + if (edited) ", edited" else ""
                customActions = buildList {
                    if (canMakeMain) add(CustomAccessibilityAction("Make main style") { onMakeMain(); true })
                    add(CustomAccessibilityAction("Remove") { onRemove(); true })
                }
            }
            .pointerInput(canMakeMain) { detectTapGestures(onLongPress = { menu = true }) },
    ) {
        StyleChip(style = name, canonical = canonical, family = family, edited = edited, onRemove = onRemove)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CrateTheme.colors.bgCard) {
            if (canMakeMain) {
                DropdownMenuItem(text = { Text("Make main style") }, onClick = { menu = false; onMakeMain() })
            }
            DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
        }
    }
}

// Vibes

/**
 * Every vibe as a chip; selected = this track is in it. Ticking makes the track a seed (an example of the vibe),
 * unticking adds it to the vibe's `exclude`. Written on Save in one `editVibes`.
 */
@Composable
private fun VibesSection(model: AppModel, vibesIn: Set<String>, setVibes: (Set<String>) -> Unit, edited: Boolean) {
    val all = model.snapshot.vibes.ordered
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            PanelLabel("Vibes", Modifier.weight(1f), trailing = if (vibesIn.isEmpty()) null else "In ${vibesIn.size}")
            if (edited) TrackEditedDot()
        }
        if (all.isEmpty()) {
            Text("No vibes yet. Create them in Library → Vibes.", style = CrateType.footnote, color = CrateTheme.colors.textSecondary)
        } else {
            ChipFlow {
                all.forEach { e ->
                    val on = e.id in vibesIn
                    VibeChip(
                        e.vibe.name, symbol = model.tracksVibeSymbol(e.id, e.vibe), count = null, isSelected = on,
                        onClick = { setVibes(if (on) vibesIn - e.id else vibesIn + e.id) },
                    )
                }
            }
            Text(
                "Tick a vibe to add this track as one of its examples; untick it to keep the track out.",
                style = CrateType.footnote, color = CrateTheme.colors.textSecondary,
            )
        }
    }
}

// BPM

@Composable
private fun BpmSection(info: TrackInfo, draft: TrackOverrideDraft, setDraft: (TrackOverrideDraft) -> Unit) {
    val colors = CrateTheme.colors
    val auto = info.track.auto
    val value = draft.bpm ?: auto?.bpm ?: 120.0
    val approx = draft.bpm == null && (auto?.bpmConf ?: 1.0) < 0.5
    val candidates = auto?.bpmCandidates.orEmpty().filter { abs(it - value) >= 0.5 }
    fun set(v: Double) {
        val clamped = v.coerceIn(40.0, 250.0)
        val a = auto?.bpm
        setDraft(draft.copy(bpm = if (a != null && abs(a - clamped) < 0.5) null else Math.round(clamped).toDouble()))
    }
    val rounded = Math.round(value).toDouble()
    Column(Modifier.fillMaxWidth().crateCard().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            PanelLabel("BPM")
            if (draft.bpm != null) TrackEditedDot()
        }
        val controls: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Segmented(listOf(Seg("÷2", "Halve") { set(value / 2) }, Seg("×2", "Double") { set(value * 2) }))
                Segmented(listOf(Seg("−", "Lower by 1") { set(rounded - 1) }, Seg("+", "Raise by 1") { set(rounded + 1) }))
            }
        }
        if (CrateTheme.isAccessibilitySize) {
            BPMText(rounded.roundToInt(), approx = approx, style = CrateType.numeralHero, color = colors.textPrimary)
            controls()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BPMText(rounded.roundToInt(), approx = approx, style = CrateType.numeralHero, color = colors.textPrimary)
                Spacer(Modifier.weight(1f).widthIn(min = Space.s))
                controls()
            }
        }
        Text(bpmCaption(info, draft), style = CrateType.footnote, color = colors.textSecondary)
        if (candidates.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text("Other candidates", style = CrateType.footnote, color = colors.textTertiary)
                candidates.forEach { c ->
                    val n = c.roundToInt()
                    Box(
                        Modifier.heightIn(min = Size.minTarget).clickable { set(c) }
                            .semantics { contentDescription = "Use $n BPM"; role = Role.Button },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$n", style = CrateType.tagLine.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary,
                            modifier = Modifier.background(colors.fillControl, RoundedCornerShape(Radius.badge))
                                .heightIn(min = 28.dp).padding(horizontal = Space.s, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun bpmCaption(info: TrackInfo, draft: TrackOverrideDraft): String {
    val auto = info.track.auto
    val detected = auto?.bpm ?: return "Set by you"
    var s = "Detected ${detected.roundToInt()}"
    auto.bpmConf?.let { s += " (${(it * 100).roundToInt()}%)" }
    s += if ((auto.bpmConf ?: 1.0) < 0.5) " · uncertain beat" else " · confident"
    if (draft.bpm != null) s = "Set by you · $s"
    return s
}

private class Seg(val title: String, val label: String, val action: () -> Unit)

/** A pill of 44 dp buttons on `fillControl` with hairline separators (÷2 ×2, − +). */
@Composable
private fun Segmented(items: List<Seg>, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    Row(
        modifier.background(colors.fillControl, RoundedCornerShape(Radius.control)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.width(1.dp).height(20.dp).background(colors.separator))
            Box(
                Modifier.size(Size.minTarget).clickable(onClick = item.action)
                    .clearAndSetSemantics { contentDescription = item.label; role = Role.Button; onClick { item.action(); true } },
                contentAlignment = Alignment.Center,
            ) {
                Text(item.title, style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
            }
        }
    }
}

// Key

@Composable
private fun KeySection(info: TrackInfo, draft: TrackOverrideDraft, setDraft: (TrackOverrideDraft) -> Unit) {
    val colors = CrateTheme.colors
    var showWheel by remember { mutableStateOf(false) }
    val autoKey = info.track.auto?.key
    val selection = draft.key ?: autoKey
    val sel = selection?.let { Camelot(it) }
    Column(
        Modifier.fillMaxWidth().crateCard().animateContentSize().padding(horizontal = Space.m, vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable { showWheel = !showWheel }
                .clearAndSetSemantics {
                    contentDescription = "Key"
                    stateDescription = (sel?.let { "${it.code}, ${it.name}" } ?: "unknown") + if (draft.key != null) ", edited" else ""
                    onClick(if (showWheel) "Hide the key wheel" else "Show the key wheel") { showWheel = !showWheel; true }
                },
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelLabel("Key")
            if (draft.key != null) TrackEditedDot()
            Spacer(Modifier.weight(1f))
            CamelotBadge(selection, size = CamelotBadgeStyle.SMALL, showsKeyName = true)
            Icon(
                if (showWheel) Icons.Filled.ExpandLess else Icons.AutoMirrored.Filled.ArrowForwardIos, null,
                Modifier.size(if (showWheel) 18.dp else 13.dp), tint = colors.textTertiary,
            )
        }
        if (showWheel) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CamelotWheel(
                    selection = selection,
                    onSelectionChange = { new -> setDraft(draft.copy(key = if (new == null || new == autoKey) null else new)) },
                    compatible = false,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
                )
            }
            val c = autoKey?.let { Camelot(it) }
            if (c != null) {
                val conf = info.track.auto?.keyConf?.let { " (${(it * 100).roundToInt()}%)" } ?: ""
                Text("Detected ${c.code} · ${c.name}$conf", style = CrateType.footnote, color = colors.textSecondary,
                     modifier = Modifier.padding(bottom = Space.s))
            }
        }
    }
}

// Energy

@Composable
private fun EnergySection(info: TrackInfo, draft: TrackOverrideDraft, setDraft: (TrackOverrideDraft) -> Unit) {
    val colors = CrateTheme.colors
    val autoEnergy = info.track.auto?.energy
    val level = draft.energy ?: autoEnergy ?: 5
    fun set(v: Int) = setDraft(draft.copy(energy = if (v == autoEnergy) null else v))
    Column(Modifier.fillMaxWidth().crateCard().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            PanelLabel("Energy")
            if (draft.energy != null) TrackEditedDot()
            Spacer(Modifier.weight(1f))
            if (draft.energy != null && autoEnergy != null) {
                Text("was $autoEnergy", style = CrateType.footnote, color = colors.textSecondary)
            }
            Text("$level", style = CrateType.numeralMedium, color = colors.textPrimary,
                 modifier = Modifier.padding(horizontal = Space.xs).clearAndSetSemantics {})
            Segmented(
                listOf(Seg("−", "Lower energy") { set(maxOf(1, level - 1)) }, Seg("+", "Raise energy") { set(minOf(10, level + 1)) }),
            )
        }
        TrackEnergyLevelControl(level, ::set)
        Text("Tap a block or use − +. Vibes use this value too.", style = CrateType.footnote, color = colors.textSecondary)
    }
}

/** A single energy value 1–10: 10 blocks (26 dp, 44 at accessibility sizes); each block is a tap target. */
@Composable
internal fun TrackEnergyLevelControl(level: Int, onChange: (Int) -> Unit) {
    val colors = CrateTheme.colors
    val height = if (CrateTheme.isAccessibilitySize) Size.minTarget else Size.energyBlock
    HapticOnChange(level)
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "Energy"
            stateDescription = "$level out of 10"
            customActions = listOf(
                CustomAccessibilityAction("Raise energy") { onChange(minOf(10, level + 1)); true },
                CustomAccessibilityAction("Lower energy") { onChange(maxOf(1, level - 1)); true },
            )
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (i in 1..10) {
            Box(
                Modifier.weight(1f).heightIn(min = Size.minTarget).clickable { onChange(i) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.fillMaxWidth().height(height)
                        .background(if (i <= level) energyColor(i, colors.isDark) else colors.fillControl, RoundedCornerShape(Radius.block)),
                )
            }
        }
    }
}

// Vocals

@Composable
private fun VocalsSection(info: TrackInfo, draft: TrackOverrideDraft, setDraft: (TrackOverrideDraft) -> Unit) {
    val colors = CrateTheme.colors
    val p = info.track.auto?.vocal
    val autoVocal = p?.let { it >= EffectiveTags.vocalThreshold }
    val isOn = draft.vocal ?: autoVocal ?: false
    fun set(v: Boolean) = setDraft(draft.copy(vocal = if (v == autoVocal) null else v))
    Column(
        Modifier.fillMaxWidth().crateCard().padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Size.minTarget)
                .clickable(role = Role.Switch) { set(!isOn) }
                .semantics(mergeDescendants = true) { if (draft.vocal != null) stateDescription = (if (isOn) "On" else "Off") + ", edited" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text("Vocals", style = CrateType.body, color = colors.textPrimary)
            if (draft.vocal != null) TrackEditedDot()
            Spacer(Modifier.weight(1f))
            Switch(
                checked = isOn, onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent,
                    uncheckedTrackColor = colors.fillControlActive, uncheckedThumbColor = colors.textSecondary,
                    uncheckedBorderColor = colors.fillControlActive,
                ),
            )
        }
        if (p != null) {
            Text(
                "Detected ${if (p >= EffectiveTags.vocalThreshold) "vocal" else "instrumental"} (${(p * 100).roundToInt()}% vocal)",
                style = CrateType.footnote, color = colors.textSecondary,
            )
        }
    }
}

// Style picker

/** "＋ Style": search every style in the taxonomy (or, without one, every style the library uses). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackStylePickerSheet(model: AppModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = CrateTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    var query by remember { mutableStateOf("") }
    val tax = model.snapshot.taxonomy
    val universe = remember(model.index) {
        val counts = HashMap<String, Int>()
        for (e in model.index.effective.values) e.primaryStyle?.let { counts.merge(it, 1, Int::plus) }
        var all = tax.genres.values.flatten().toSet()
        if (all.isEmpty()) all = model.index.tracks.values.flatMap { it.auto?.styles?.keys.orEmpty() }.toSet()
        all.map { it to (counts[it] ?: 0) }
    }
    val results = remember(universe, query) {
        val q = query.trim().lowercase()
        universe.filter { (s, _) ->
            q.isEmpty() || tax.label(s).let { it.styleDisplay.lowercase().contains(q) || it.genreDisplay.lowercase().contains(q) }
        }.sortedWith(
            compareByDescending<Pair<String, Int>> { q.isNotEmpty() && tax.label(it.first).styleDisplay.lowercase().startsWith(q) }
                .thenByDescending { it.second }.thenBy { it.first },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = colors.bgSheet, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding().imePadding()) {
            SheetTopBar("Cancel", close, null, {}, title = "Add style")
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search styles", color = colors.textTertiary) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.textTertiary) },
                shape = RoundedCornerShape(Radius.control),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.fillControl, unfocusedContainerColor = colors.fillControl,
                    focusedBorderColor = colors.fillControl, unfocusedBorderColor = colors.fillControl,
                    cursorColor = colors.textPrimary,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.screenMargin, vertical = Space.xs),
            )
            val fmt = remember { NumberFormat.getIntegerInstance() }
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = Space.screenMargin).padding(top = Space.s)) {
                items(results, key = { it.first }) { (style, count) ->
                    val label = tax.label(style)
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(style); close() }.heightIn(min = Size.minTarget)
                                .padding(horizontal = Space.m, vertical = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            Text(label.styleDisplay, style = CrateType.body, color = colors.textPrimary)
                            Text("· ${label.genreDisplay}", style = CrateType.footnote, color = colors.textSecondary, modifier = Modifier.weight(1f))
                            if (count > 0) Text(fmt.format(count), style = CrateType.tagLine, color = colors.textTertiary)
                        }
                        HorizontalDivider(color = colors.separator)
                    }
                }
            }
        }
    }
}
