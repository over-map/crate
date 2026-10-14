package com.overmap.crate.screens.build

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.LibraryIndex
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.FamilyDot
import com.overmap.crate.ui.components.GenreSummary
import com.overmap.crate.ui.components.HapticOnChange
import com.overmap.crate.ui.components.StyleChip
import com.overmap.crate.ui.components.StyleChipVariant
import com.overmap.crate.ui.components.TickRing
import com.overmap.crate.ui.components.TickState
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.crateIcon
import kotlinx.coroutines.launch

private const val TOP_STYLES = 8
private val ROW_INDENT = 34.dp

/**
 * Genre & style picker sheet (6g, 2f–2l): the port of iOS `GenrePickerView`.
 *
 * Edits a draft of `model.build.families` (family → ticked styles). Picking a family ticks all its styles; untick
 * the ones you don't want. Done commits, Cancel discards, Clear empties the draft. [focusFamily] is expanded (and
 * scrolled to) when the sheet opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenrePickerSheet(onDismiss: () -> Unit, focusFamily: String? = null) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(model.build.families.filter { it.value.isNotEmpty() }) }
    var expanded by remember { mutableStateOf(setOfNotNull(focusFamily)) }
    var showAll by remember { mutableStateOf(emptySet<String>()) }
    var search by remember { mutableStateOf("") }
    val trimmed = search.trim()
    val scroll = rememberScrollState()
    val rowY = remember { mutableStateMapOf<String, Int>() }
    var listTop by remember { mutableStateOf(0) }

    fun close(commit: Boolean) {
        if (commit) model.updateBuild { it.copy(families = draft) }
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun styleCount(fam: String) = model.styles(fam).size
    fun familyState(fam: String): TickState {
        val ticked = draft[fam]
        if (ticked.isNullOrEmpty()) return TickState.EMPTY
        return if (ticked.size >= styleCount(fam)) TickState.CHECKED else TickState.MIXED
    }
    fun toggleFamily(fam: String) {
        draft = if (familyState(fam) == TickState.CHECKED) draft - fam else draft + (fam to model.styles(fam).toSet())
    }
    fun toggleStyle(style: String, fam: String) {
        val set = draft[fam] ?: emptySet()
        val next = if (style in set) set - style else set + style
        draft = if (next.isEmpty()) draft - fam else draft + (fam to next)
    }

    // Scroll only when the genre is further down than the first rows.
    LaunchedEffect(Unit) {
        val idx = model.index.families.indexOfFirst { it.family == focusFamily }
        if (focusFamily != null && idx > 1) {
            kotlinx.coroutines.delay(350)
            rowY[focusFamily]?.let { scroll.animateScrollTo(listTop + it) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgSheet,
        contentColor = colors.textPrimary,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            SheetHeader(
                title = "Genre & style",
                onCancel = { close(false) },
                actionTitle = "Clear",
                actionEnabled = draft.isNotEmpty(),
                onAction = { draft = emptyMap() },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = Space.screenMargin)
                    .padding(bottom = Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
            ) {
                PickerSearchField(search, onChange = { search = it })
                if (trimmed.isEmpty()) {
                    // Most used
                    val top = model.index.families
                        .flatMap { f -> f.styles.map { Triple(f.family, it.first, it.second) } }
                        .sortedByDescending { it.third }
                        .take(8)
                    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
                        BuildSectionLabel("Most used")
                        Row(
                            Modifier.bleed(Space.screenMargin).horizontalScroll(rememberScrollState())
                                .padding(horizontal = Space.screenMargin, vertical = Space.xs),
                            horizontalArrangement = Arrangement.spacedBy(Space.chipGap),
                        ) {
                            top.forEach { (fam, style, count) ->
                                val on = draft[fam]?.contains(style) == true
                                StyleChip(
                                    style = model.styleName(style), canonical = style, family = model.familyHue(fam),
                                    variant = if (on) StyleChipVariant.Selected else StyleChipVariant.Off(count),
                                    onClick = { toggleStyle(style, fam) },
                                )
                            }
                        }
                    }
                    // Library list
                    Column(
                        Modifier.onGloballyPositioned { listTop = it.positionInParent().y.toInt() },
                        verticalArrangement = Arrangement.spacedBy(Space.labelGap),
                    ) {
                        BuildSectionLabel("In your library · by tracks")
                        Text(
                            "Each track has one genre, voted by up to four sources. Picking a genre ticks all its styles; untick the ones you don't want.",
                            style = CrateType.footnote, color = colors.textSecondary,
                        )
                        Column(Modifier.fillMaxWidth().crateCard()) {
                            model.index.families.forEachIndexed { i, fam ->
                                if (i > 0) RowDivider(0.dp)
                                Box(Modifier.onGloballyPositioned { rowY[fam.family] = it.positionInParent().y.toInt() }) {
                                    FamilyRow(
                                        fam = fam,
                                        state = familyState(fam.family),
                                        ticked = draft[fam.family]?.size ?: 0,
                                        isOpen = fam.family in expanded,
                                        onToggleOpen = {
                                            expanded = if (fam.family in expanded) expanded - fam.family else expanded + fam.family
                                        },
                                        onTick = { toggleFamily(fam.family) },
                                    )
                                }
                                if (fam.family in expanded) {
                                    val all = fam.styles
                                    val visible = if (fam.family in showAll) all else all.take(TOP_STYLES)
                                    visible.forEach { (style, count) ->
                                        RowDivider(ROW_INDENT)
                                        StyleRow(model.styleName(style), count, draft[fam.family]?.contains(style) == true) {
                                            toggleStyle(style, fam.family)
                                        }
                                    }
                                    if (all.size > TOP_STYLES) {
                                        RowDivider(ROW_INDENT)
                                        val open = fam.family in showAll
                                        Text(
                                            if (open) "Show fewer" else "Show ${all.size - TOP_STYLES} more",
                                            style = CrateType.secondary, color = colors.textSecondary,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = Size.minTarget)
                                                .clickable { showAll = if (open) showAll - fam.family else showAll + fam.family }
                                                .padding(start = Space.m + ROW_INDENT, top = Space.m, bottom = Space.m),
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    SearchResults(
                        query = trimmed,
                        isChecked = { fam, style ->
                            if (style == null) familyState(fam) else if (draft[fam]?.contains(style) == true) TickState.CHECKED else TickState.EMPTY
                        },
                        onToggle = { fam, style -> if (style == null) toggleFamily(fam) else toggleStyle(style, fam) },
                    )
                }
            }
            val count = if (draft.isEmpty()) model.index.tracks.size else model.count { it.copy(families = draft) }
            SheetBottomBar(count = count, caption = pickerSummary(draft), onDone = { close(true) })
        }
    }
}

/** "House except Garage House or Deep House, Tech House" (bottom bar caption). */
@Composable
private fun pickerSummary(draft: Map<String, Set<String>>): String {
    val model = rememberModel()
    if (draft.isEmpty()) return "Any genre"
    val order = model.index.families.map { it.family }
    return draft.keys.sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }.joinToString(" or ") { fam ->
        val name = model.familyName(fam)
        when (val s = GenreSummary.make(draft[fam] ?: emptySet(), model.styles(fam))) {
            GenreSummary.AllStyles -> name
            is GenreSummary.Except -> "$name except ${s.names.joinToString(", ") { model.styleName(it) }}"
            is GenreSummary.Styles -> s.names.joinToString(", ") { model.styleName(it) }
            is GenreSummary.Count -> "$name · ${s.ticked} of ${s.total} styles"
        }
    }
}

@Composable
private fun FamilyRow(
    fam: LibraryIndex.FamilyEntry,
    state: TickState,
    ticked: Int,
    isOpen: Boolean,
    onToggleOpen: () -> Unit,
    onTick: () -> Unit,
) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val name = model.familyName(fam.family)
    val detail = if (state == TickState.MIXED) "${fmt(fam.count)} tracks · $ticked of ${fam.styles.size} styles"
    else "${fmt(fam.count)} tracks · ${fam.styles.size} styles"
    val rotation by animateFloatAsState(if (isOpen) 90f else 0f, label = "chevron")
    HapticOnChange(state)
    Row(Modifier.fillMaxWidth().padding(start = Space.m, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(onClick = onToggleOpen)
                .clearAndSetSemantics {
                    contentDescription = "$name, $detail"
                    stateDescription = if (isOpen) "Expanded" else "Collapsed"
                    role = Role.Button
                    onClick(label = if (isOpen) "Hide the styles" else "Show the styles") { onToggleOpen(); true }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            FamilyDot(model.familyHue(fam.family))
            Column(Modifier.weight(1f)) {
                Text(name, style = CrateType.headline, color = colors.textPrimary)
                Text(detail, style = CrateType.footnote, color = colors.textSecondary)
            }
            Icon(crateIcon("chevron.right"), null, Modifier.size(20.dp).rotate(rotation), tint = colors.textTertiary)
        }
        Box(
            Modifier
                .size(Size.minTarget)
                .clickable(onClick = onTick)
                .clearAndSetSemantics {
                    contentDescription = "Pick $name"
                    stateDescription = when (state) {
                        TickState.CHECKED -> "All styles"
                        TickState.MIXED -> "Some styles"
                        TickState.EMPTY -> "Not picked"
                    }
                    role = Role.Checkbox
                    onClick { onTick(); true }
                },
            contentAlignment = Alignment.Center,
        ) { TickRing(state) }
    }
}

@Composable
private fun StyleRow(name: String, count: Int, on: Boolean, onToggle: () -> Unit) {
    val colors = CrateTheme.colors
    HapticOnChange(on)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Size.minTarget)
            .clickable(onClick = onToggle)
            .clearAndSetSemantics {
                contentDescription = "$name, $count tracks"
                selected = on
                role = Role.Checkbox
                onClick { onToggle(); true }
            }
            .padding(start = Space.m + ROW_INDENT, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(name, Modifier.weight(1f), style = CrateType.body, color = colors.textPrimary)
        Text(fmt(count), style = CrateType.secondary.copy(fontFeatureSettings = "tnum"), color = colors.textSecondary)
        Box(Modifier.size(Size.minTarget), contentAlignment = Alignment.Center) { TickRing(on) }
    }
}

private data class StyleHit(val family: String, val style: String?, val name: String, val count: Int)

@Composable
private fun SearchResults(query: String, isChecked: (String, String?) -> TickState, onToggle: (String, String?) -> Unit) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val q = query.lowercase()
    val hits = buildList {
        for (fam in model.index.families) {
            val famName = model.familyName(fam.family)
            if (famName.lowercase().contains(q)) add(StyleHit(fam.family, null, famName, fam.count))
            for ((style, count) in fam.styles) {
                val name = model.styleName(style)
                if (name.lowercase().contains(q)) add(StyleHit(fam.family, style, name, count))
            }
        }
    }.sortedWith(compareByDescending<StyleHit> { it.name.lowercase().startsWith(q) }.thenByDescending { it.count })

    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        BuildSectionLabel(if (hits.isEmpty()) "No matches" else "${hits.size} in your library")
        if (hits.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().crateCard()) {
                hits.forEachIndexed { i, hit ->
                    if (i > 0) RowDivider(ROW_INDENT)
                    val state = isChecked(hit.family, hit.style)
                    val sub = if (hit.style == null) "Genre · ${model.styles(hit.family).size} styles" else model.familyName(hit.family)
                    HapticOnChange(state)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable { onToggle(hit.family, hit.style) }
                            .clearAndSetSemantics {
                                contentDescription = "${hit.name}, ${if (hit.style == null) "genre" else model.familyName(hit.family)}, ${hit.count} tracks"
                                selected = state == TickState.CHECKED
                                role = Role.Checkbox
                                onClick { onToggle(hit.family, hit.style); true }
                            }
                            .padding(horizontal = Space.m, vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        FamilyDot(model.familyHue(hit.family))
                        Column(Modifier.weight(1f)) {
                            Text(highlight(hit.name, query), style = CrateType.body, color = colors.textPrimary)
                            Text(sub, style = CrateType.footnote, color = colors.textSecondary)
                        }
                        Text(fmt(hit.count), style = CrateType.secondary.copy(fontFeatureSettings = "tnum"), color = colors.textSecondary)
                        TickRing(state)
                    }
                }
            }
        }
        Text("Only styles with tracks in your library are listed.", style = CrateType.footnote, color = colors.textSecondary)
    }
}

private fun highlight(name: String, query: String): AnnotatedString = buildAnnotatedString {
    val at = name.indexOf(query, ignoreCase = true)
    if (at < 0 || query.isEmpty()) { append(name); return@buildAnnotatedString }
    append(name.substring(0, at))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name.substring(at, at + query.length)) }
    append(name.substring(at + query.length))
}

@Composable
private fun RowDivider(indent: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .padding(start = if (indent == 0.dp) 0.dp else Space.m + indent)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(CrateTheme.colors.separator),
    )
}

// region Shared sheet chrome

/** The sheet navigation row: Cancel · centred title · trailing action (Clear, Any key). */
@Composable
internal fun SheetHeader(title: String, onCancel: () -> Unit, actionTitle: String, actionEnabled: Boolean, onAction: () -> Unit) {
    val colors = CrateTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Space.xs)) {
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) {
            Text("Cancel", style = CrateType.body, color = colors.textSecondary)
        }
        Text(
            title, style = CrateType.headline, color = colors.textPrimary, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        TextButton(onClick = onAction, enabled = actionEnabled, modifier = Modifier.align(Alignment.CenterEnd)) {
            Text(actionTitle, style = CrateType.body, color = if (actionEnabled) colors.textPrimary else colors.textTertiary)
        }
    }
}

/** The search field at the top of the picker. */
@Composable
private fun PickerSearchField(text: String, onChange: (String) -> Unit) {
    val colors = CrateTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTarget)
            .background(colors.fillControl, RoundedCornerShape(Radius.card))
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(crateIcon("magnifyingglass"), null, Modifier.size(18.dp), tint = colors.textSecondary)
        BasicTextField(
            value = text, onValueChange = onChange, singleLine = true,
            textStyle = CrateType.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Search genres and styles" },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Search genres and styles", style = CrateType.body, color = colors.textTertiary)
                    inner()
                }
            },
        )
        if (text.isNotEmpty()) {
            Box(
                Modifier.size(32.dp).clickable { onChange("") }
                    .clearAndSetSemantics { contentDescription = "Clear search"; role = Role.Button; onClick { onChange(""); true } },
                contentAlignment = Alignment.Center,
            ) { Icon(crateIcon("xmark.circle.fill"), null, Modifier.size(18.dp), tint = colors.textTertiary) }
        }
    }
}

// endregion
