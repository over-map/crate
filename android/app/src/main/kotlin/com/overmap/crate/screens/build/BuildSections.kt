package com.overmap.crate.screens.build

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.BuildState
import com.overmap.crate.core.Query
import com.overmap.crate.core.Tags
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.AddChip
import com.overmap.crate.ui.components.CamelotBadge
import com.overmap.crate.ui.components.CamelotBadgeStyle
import com.overmap.crate.ui.components.ChipFlow
import com.overmap.crate.ui.components.ChipSeparator
import com.overmap.crate.ui.components.CountText
import com.overmap.crate.ui.components.EnergyRangeControl
import com.overmap.crate.ui.components.GenreSummary
import com.overmap.crate.ui.components.GenreSummaryChip
import com.overmap.crate.ui.components.HapticOnChange
import com.overmap.crate.ui.components.RangeSlider
import com.overmap.crate.ui.components.StyleChip
import com.overmap.crate.ui.components.TagChip
import com.overmap.crate.ui.components.TagSuggestionRow
import com.overmap.crate.ui.components.VibeChip
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.Stroke
import com.overmap.crate.ui.theme.crateIcon
import kotlin.math.ceil
import kotlin.math.floor

// region Genre & style

/**
 * Picked genres as summary chips ("House · all styles", "House · except Garage House") or one StyleChip per ticked
 * style (1–3), separated by "OR"; "＋ Add" opens the picker.
 */
@Composable
internal fun BuildGenreSection(onOpenPicker: (String?) -> Unit) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val order = model.index.families.map { it.family }
    val picked = model.build.families.filter { it.value.isNotEmpty() }.keys
        .sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }

    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        BuildSectionLabel(if (picked.isEmpty()) "Genre & style" else "Genre & style · any of")
        if (picked.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                AddChip("Add genre or style", onClick = { onOpenPicker(null) })
                Text("Any", style = CrateType.footnote, color = colors.textTertiary)
            }
        } else {
            // Family chips, or one chip per ticked style when 1–3 are ticked.
            val chips: List<Pair<String, String?>> = picked.flatMap { fam ->
                val ticked = model.build.families[fam] ?: emptySet()
                val summary = GenreSummary.make(ticked, model.styles(fam))
                if (summary is GenreSummary.Styles) summary.names.map { fam to it } else listOf(fam to null)
            }
            ChipFlow {
                chips.forEachIndexed { i, (fam, style) ->
                    if (i > 0) ChipSeparator()
                    if (style == null) {
                        val raw = GenreSummary.make(model.build.families[fam] ?: emptySet(), model.styles(fam))
                        val named = if (raw is GenreSummary.Except) GenreSummary.Except(raw.names.map { model.styleName(it) }) else raw
                        GenreSummaryChip(
                            family = model.familyHue(fam), summary = named, name = model.familyName(fam),
                            onClick = { onOpenPicker(fam) },
                            onRemove = { model.updateBuild { it.copy(families = it.families - fam) } },
                        )
                    } else {
                        StyleChip(
                            style = model.styleName(style), canonical = style, family = model.familyHue(fam),
                            genre = model.familyName(fam),
                            onClick = { onOpenPicker(fam) },
                            onRemove = {
                                model.updateBuild { b ->
                                    val left = (b.families[fam] ?: emptySet()) - style
                                    b.copy(families = if (left.isEmpty()) b.families - fam else b.families + (fam to left))
                                }
                            },
                        )
                    }
                }
                AddChip("Add", onClick = { onOpenPicker(null) })
            }
        }
    }
}

// endregion

// region Vibe

/** Vibe chips in the user's order, with counts (or "count if added" once filters are set). Long-press edits. */
@Composable
internal fun BuildVibeSection(onEdit: (String?) -> Unit, onManage: () -> Unit) {
    val model = rememberModel()
    val build = model.build
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        BuildSectionLabel(if (build.vibes.size >= 2) "Vibe · all of" else "Vibe") {
            if (build.isEmpty) {
                Box(
                    Modifier
                        .heightIn(min = Size.minTarget / 2)
                        .clickable(onClick = onManage)
                        .clearAndSetSemantics { contentDescription = "Edit vibe order"; role = Role.Button; onClick { onManage(); true } },
                    contentAlignment = Alignment.Center,
                ) { TrailingPanelLabel("Your order · Edit") }
            } else {
                TrailingPanelLabel("Count if added")
            }
        }
        ChipFlow {
            model.snapshot.vibes.ordered.forEach { (id, vibe) ->
                val selected = id in build.vibes
                val hasSeeds = vibeHasSeeds(vibe)
                val count: Int? = when {
                    selected || !hasSeeds -> null
                    build.isEmpty -> model.index.vibeMembers[id]?.size ?: 0
                    else -> model.count { it.copy(vibes = it.vibes + id) }
                }
                VibeChip(
                    name = vibe.name, symbol = vibeSymbol(id, vibe), count = count, isSelected = selected, hasSeeds = hasSeeds,
                    onClick = {
                        if (!hasSeeds) onEdit(id)
                        else model.updateBuild { b -> b.copy(vibes = if (id in b.vibes) b.vibes - id else b.vibes + id) }
                    },
                    onEdit = { onEdit(id) },
                )
            }
            AddChip("New vibe", onClick = { onEdit(null) })
        }
    }
}

// endregion

// region Tags

/** The tag field with picked TagChips inside, autocomplete card, unknown-term card and footnote (6c–6e). */
@Composable
internal fun BuildTagSection(
    text: String,
    onTextChange: (String) -> Unit,
    focused: Boolean,
    onFocusedChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val trimmed = text.trim()
    val showCard = focused && trimmed.isNotEmpty()
    val suggestions = if (showCard) {
        model.index.tagSuggestions(trimmed, limit = 12).filter { it.first !in model.build.tags }.take(6)
    } else emptyList()

    fun add(tag: String) {
        model.updateBuild { b -> if (tag in b.tags) b else b.copy(tags = b.tags + tag) }
        onTextChange("")
        // Picking a tag ends the gesture: hide the keyboard so the result is visible (tap the field to add another).
        keyboard?.hide()
        focusManager.clearFocus()
    }

    fun submit() {
        val norm = Tags.normalise(trimmed)
        when {
            model.index.tagCounts.containsKey(norm) -> add(norm)
            suggestions.isNotEmpty() -> add(suggestions.first().first)
            else -> { keyboard?.hide(); focusManager.clearFocus() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        BuildSectionLabel("Tags") {
            if (showCard && suggestions.isNotEmpty()) TrailingPanelLabel("If added · all")
        }

        // Field
        val shape = RoundedCornerShape(Radius.card)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(colors.fillControl, shape)
                .then(if (focused) Modifier.border(Stroke.outline, colors.textPrimary, shape) else Modifier)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    runCatching { focusRequester.requestFocus() }
                    keyboard?.show()
                }
                .padding(horizontal = Space.m, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(crateIcon("magnifyingglass"), null, Modifier.size(18.dp), tint = colors.textSecondary)
            ChipFlow(Modifier.weight(1f)) {
                model.build.tags.forEach { tag ->
                    TagChip(tag, onRemove = { model.updateBuild { b -> b.copy(tags = b.tags - tag) } })
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = CrateType.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.textPrimary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .heightIn(min = Size.chipHeight)
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusedChange(it.isFocused) }
                        .semantics { contentDescription = "Search tags" },
                    decorationBox = { inner ->
                        Box(Modifier.heightIn(min = Size.chipHeight), contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    if (model.build.tags.isEmpty()) "Search tags: italian, dub techno…" else "Add tag",
                                    style = CrateType.body, color = colors.textTertiary, maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (text.isNotEmpty()) {
                Box(
                    Modifier
                        .size(Size.minTarget / 1.5f)
                        .clickable { onTextChange("") }
                        .clearAndSetSemantics { contentDescription = "Clear search"; role = Role.Button; onClick { onTextChange(""); true } },
                    contentAlignment = Alignment.Center,
                ) { Icon(crateIcon("xmark.circle.fill"), null, Modifier.size(18.dp), tint = colors.textTertiary) }
            }
        }

        if (showCard) {
            if (suggestions.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().crateCard().padding(Space.m).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text("No tracks tagged “$trimmed”", style = CrateType.headline, color = colors.textPrimary)
                    Text(
                        "Tags are the words listeners use for an artist on Last.fm, Discogs and Beatport. Try a broader word.",
                        style = CrateType.secondary, color = colors.textSecondary,
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth().crateCard()) {
                    suggestions.forEachIndexed { i, (tag, total) ->
                        if (i > 0) Box(Modifier.padding(start = Space.m).fillMaxWidth().height(0.5.dp).background(colors.separator))
                        TagSuggestionRow(
                            name = tag, query = trimmed,
                            countIfAdded = model.count { it.copy(tags = it.tags + tag) },
                            libraryTotal = total,
                            onClick = { add(tag) },
                        )
                    }
                }
            }
        } else {
            Text(
                "Community tags from Last.fm, Discogs and Beatport · ${fmt(model.communityTaggedCount)} tracks",
                style = CrateType.footnote, color = colors.textSecondary,
            )
        }
    }
}

// endregion

// region BPM

/**
 * BPM: tap a numeral to type it (number pad + the [BpmKeyboardBar]), the range slider with half/double ghosts and
 * the ±3 / ±6 / Any quick chips. Editing state is owned by BuildScreen so the keyboard bar can sit above the IME.
 */
@Composable
internal fun BuildBPMSection(
    editing: BpmBound?,
    text: String,
    onTextChange: (String) -> Unit,
    onStartEditing: (BpmBound) -> Unit,
    onStopEditing: (BpmBound) -> Unit,
    focusRequesters: Map<BpmBound, FocusRequester>,
) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val focusManager = LocalFocusManager.current
    val r = model.build.bpm
    val isAny = r == BuildState.BPM_ANY
    val ghosts = bpmGhosts(r, model.build.halfDouble)

    /** Clamped to 60–200 and min ≤ max; the slider and count update as you type. */
    fun apply(typed: String) {
        val bound = editing ?: return
        val digits = typed.filter { it.isDigit() }.take(3)
        onTextChange(digits)
        val b = BuildState.BPM_ANY
        // "13" on the way to "138" isn't applied (it would clamp to 60 and jump the slider).
        val v = digits.toIntOrNull() ?: return
        if (v < b.first) return
        val clamped = v.coerceIn(b.first, b.last)
        model.updateBuild { s ->
            when (bound) {
                BpmBound.MIN -> s.copy(bpm = clamped..maxOf(clamped, s.bpm.last))
                BpmBound.MAX -> s.copy(bpm = minOf(clamped, s.bpm.first)..clamped)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        BuildSectionLabel("BPM") {
            BuildSwitch(
                checked = model.build.halfDouble,
                onCheckedChange = { on -> model.updateBuild { it.copy(halfDouble = on) } },
            ) { Text("Half/double too", style = CrateType.footnote, color = colors.textSecondary) }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val numerals: @Composable () -> Unit = {
                BpmNumeral(BpmBound.MIN, r.first, isAny, editing, text, ::apply, onStartEditing, onStopEditing,
                           focusRequesters.getValue(BpmBound.MIN)) { focusManager.clearFocus() }
            }
            if (CrateTheme.isAccessibilitySize) {
                Column {
                    numerals()
                    Text("–", style = CrateType.numeralHero, color = colors.textTertiary, modifier = Modifier.clearAndSetSemantics {})
                    BpmNumeral(BpmBound.MAX, r.last, isAny, editing, text, ::apply, onStartEditing, onStopEditing,
                               focusRequesters.getValue(BpmBound.MAX)) { focusManager.clearFocus() }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    numerals()
                    Text("–", style = CrateType.numeralHero, color = colors.textTertiary, modifier = Modifier.clearAndSetSemantics {})
                    BpmNumeral(BpmBound.MAX, r.last, isAny, editing, text, ::apply, onStartEditing, onStopEditing,
                               focusRequesters.getValue(BpmBound.MAX)) { focusManager.clearFocus() }
                }
            }
            Spacer(Modifier.weight(1f).width(Space.s))
            ghosts.firstOrNull()?.let { g ->
                Text(
                    "also ${g.first}–${g.last}", style = CrateType.footnote.copy(fontFeatureSettings = "tnum"),
                    color = colors.textSecondary, modifier = Modifier.padding(bottom = Space.s),
                )
            }
        }
        RangeSlider(
            range = r,
            onRangeChange = { nr -> model.updateBuild { it.copy(bpm = nr) } },
            bounds = BuildState.BPM_ANY,
            ghosts = ghosts,
        )
        // Quick chips: ±3 around the centre, ±6, Any
        val centre = if (isAny) 120 else (r.first + r.last) / 2
        Row(horizontalArrangement = Arrangement.spacedBy(Space.chipGap)) {
            QuickChip("±3", "Plus or minus 3 around $centre", around(centre, 3))
            QuickChip("±6", "Plus or minus 6 around $centre", around(centre, 6))
            QuickChip("Any", "Any BPM", BuildState.BPM_ANY)
        }
    }
}

/** The half and double ranges, clamped to the scale (shown as ghosts and "also 68–71"). */
internal fun bpmGhosts(r: IntRange, halfDouble: Boolean): List<IntRange> {
    val b = BuildState.BPM_ANY
    if (!halfDouble || r == b) return emptyList()
    val half = ceil(r.first / 2.0).toInt()..floor(r.last / 2.0).toInt()
    val dbl = (r.first * 2)..(r.last * 2)
    return listOf(half, dbl).filter { g -> g.first <= g.last && g.first <= b.last && g.last >= b.first }
        .map { g -> maxOf(g.first, b.first)..minOf(g.last, b.last) }
}

private fun around(c: Int, d: Int): IntRange {
    val b = BuildState.BPM_ANY
    return maxOf(b.first, c - d)..minOf(b.last, c + d)
}

@Composable
private fun QuickChip(title: String, spoken: String, range: IntRange) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val selected = model.build.bpm == range
    HapticOnChange(selected)
    val set = { model.updateBuild { it.copy(bpm = range) } }
    Box(
        Modifier
            .heightIn(min = Size.minTarget)
            .background(if (selected) colors.accent else colors.fillControl, RoundedCornerShape(Radius.chip))
            .clickable { set() }
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                this.selected = selected
                onClick { set(); true }
            }
            .padding(horizontal = Space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title, style = CrateType.chipLabel.copy(fontFeatureSettings = "tnum"),
            color = if (selected) colors.onAccent else colors.textPrimary,
        )
    }
}

/** One tap-to-type numeral: dotted underline; while typed, an outlined box with the number pad. */
@Composable
private fun BpmNumeral(
    bound: BpmBound,
    value: Int,
    isAny: Boolean,
    editing: BpmBound?,
    text: String,
    onType: (String) -> Unit,
    onStartEditing: (BpmBound) -> Unit,
    onStopEditing: (BpmBound) -> Unit,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
) {
    val colors = CrateTheme.colors
    val isEditing = editing == bound
    val label = if (bound == BpmBound.MIN) "Minimum BPM $value" else "Maximum BPM $value"
    val start = {
        onStartEditing(bound)
        runCatching { focusRequester.requestFocus() }
        Unit
    }
    Box(
        Modifier
            .then(
                if (isEditing) Modifier.border(1.5.dp, colors.textPrimary, RoundedCornerShape(Radius.control)).padding(horizontal = Space.xs)
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The field stays composed so focus can move to it; it shows only while editing.
        BasicTextField(
            value = if (isEditing) text else "",
            onValueChange = { if (isEditing) onType(it) },
            singleLine = true,
            textStyle = CrateType.numeralHero.copy(color = colors.textPrimary, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .matchParentSize()
                .alpha(if (isEditing) 1f else 0f)
                .focusRequester(focusRequester)
                .onFocusChanged { f ->
                    if (f.isFocused && !isEditing) onStartEditing(bound)
                    if (!f.isFocused && isEditing) onStopEditing(bound)
                }
                .then(if (isEditing) Modifier.semantics { contentDescription = if (bound == BpmBound.MIN) "Minimum BPM" else "Maximum BPM" } else Modifier.clearAndSetSemantics {}),
        )
        Text(
            value.toString(),
            style = CrateType.numeralHero,
            color = if (isAny) colors.textTertiary else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier
                .alpha(if (isEditing) 0f else 1f)
                .drawBehind {
                    val y = size.height - 4.dp.toPx()
                    drawLine(
                        colors.textTertiary, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                    )
                }
                .then(
                    if (isEditing) Modifier.clearAndSetSemantics {}
                    else Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { start() }
                        .clearAndSetSemantics {
                            contentDescription = label
                            stateDescription = "Type a value"
                            role = Role.Button
                            onClick { start(); true }
                        },
                ),
        )
    }
}

// endregion

// region Energy

@Composable
internal fun BuildEnergySection() {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val r = model.build.energy
    val isAny = r == BuildState.ENERGY_ANY
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        BuildSectionLabel("Energy") {
            Text(
                when {
                    isAny -> "Any"
                    r.first == r.last -> "${r.first}"
                    else -> "${r.first}–${r.last}"
                },
                style = CrateType.numeralMedium,
                color = if (isAny) colors.textTertiary else colors.textPrimary,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        EnergyRangeControl(range = r, onRangeChange = { nr -> model.updateBuild { it.copy(energy = nr) } })
    }
}

// endregion

// region Key + vocals

@Composable
internal fun BuildKeyVocalSection(onOpenKey: () -> Unit) {
    if (CrateTheme.isAccessibilitySize) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sectionGap)) {
            KeyButton(onOpenKey, Modifier.fillMaxWidth())
            VocalsControl(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Top) {
            KeyButton(onOpenKey, Modifier.weight(1f))
            VocalsControl(Modifier.weight(1f))
        }
    }
}

@Composable
private fun KeyButton(onOpenKey: () -> Unit, modifier: Modifier) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val key = model.build.key
    val label = key?.let { "Key $it${if (model.build.compatible) " with compatible keys" else ""}" } ?: "Key, any"
    Row(
        modifier
            .heightIn(min = Size.minTarget)
            .crateCard()
            .clickable(onClick = onOpenKey)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = "Opens the key picker") { onOpenKey(); true }
            }
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        TrailingPanelLabel("Key")
        CamelotBadge(code = key, size = CamelotBadgeStyle.SMALL)
        if (key != null && model.build.compatible) {
            Text("+ compatible", style = CrateType.footnote, color = colors.textSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun VocalsControl(modifier: Modifier) {
    val model = rememberModel()
    val values = listOf(Query.Vocal.any, Query.Vocal.vocal, Query.Vocal.instrumental)
    BuildSegmented(
        options = listOf("Any", "Vocal", "Instr."),
        labels = listOf("Vocals any", "Vocal", "Instrumental"),
        selected = values.indexOf(model.build.vocal),
        onSelect = { i -> model.updateBuild { it.copy(vocal = values[i]) } },
        modifier = modifier,
    )
}

// endregion

// region Limit

@Composable
internal fun BuildLimitSection() {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val limit = model.build.limit
    fun set(v: Int) = model.updateBuild { it.copy(limit = v.coerceIn(10, 500)) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        BuildSectionLabel("Limit")
        val value: @Composable (Modifier) -> Unit = { m ->
            Row(m, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                CountText(limit, style = CrateType.numeralMedium, color = colors.textPrimary)
                Text("tracks max · shuffled", style = CrateType.footnote, color = colors.textSecondary,
                     modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (CrateTheme.isAccessibilitySize) value(Modifier.semantics(mergeDescendants = true) {})
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = "$limit tracks" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (CrateTheme.isAccessibilitySize) Spacer(Modifier.weight(1f)) else value(Modifier.weight(1f))
            // Stepper ±10 (10–500)
            Row(
                Modifier.background(colors.fillControl, RoundedCornerShape(Radius.control)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperHalf("minus", "Decrease limit", enabled = limit > 10) { set(limit - 10) }
                Box(Modifier.width(1.dp).height(18.dp).background(colors.separator))
                StepperHalf("plus", "Increase limit", enabled = limit < 500) { set(limit + 10) }
            }
        }
    }
}

@Composable
private fun StepperHalf(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = CrateTheme.colors
    Box(
        Modifier
            .size(width = 52.dp, height = Size.minTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                if (enabled) onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(crateIcon(symbol), null, Modifier.size(20.dp), tint = if (enabled) colors.textPrimary else colors.textTertiary)
    }
}

// endregion
