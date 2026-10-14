package com.overmap.crate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import java.text.Normalizer

/**
 * A picked community tag: `accent` fill, the tag glyph (⌗), the name and an optional ×. The glyph keeps tags from
 * ever reading as vibes. Tags combine with AND.
 */
@Composable
fun TagChip(name: String, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val colors = CrateTheme.colors
    ChipChrome(
        colors.accent, colors.onAccent,
        modifier.clearAndSetSemantics {
            contentDescription = "Tag $name"
            selected = true
            chipActions(onRemove = onRemove)
        },
    ) {
        Icon(Icons.Filled.Tag, null, Modifier.size(14.dp))
        ChipText(name)
        if (onRemove != null) {
            Box(Modifier.widthIn(min = Space.l).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, null, Modifier.size(13.dp).alpha(0.6f))
            }
        }
    }
}

/**
 * One autocomplete row in the tag suggestions card: ⌗ · name (the matched part bold) · count if added · library
 * total (tertiary). Rows whose count-if-added is 0 dim to 45 %. Minimum height 44 dp.
 */
@Composable
fun TagSuggestionRow(
    name: String,
    query: String,
    countIfAdded: Int?,
    libraryTotal: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CrateTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = buildList {
        add("Tag $name")
        countIfAdded?.let { add("$it if added") }
        add("$libraryTotal in library")
    }.joinToString(", ")
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = label; button(onClick) }
            .alpha(if (countIfAdded == 0) Metric.DIMMED else 1f)
            .background(if (pressed) colors.fillControl else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .heightIn(min = Size.minTarget)
            .padding(horizontal = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tag, null, Modifier.size(14.dp), tint = colors.textSecondary)
        Text(highlight(name, query), Modifier.weight(1f), style = CrateType.body, color = colors.textPrimary, maxLines = 1)
        if (countIfAdded != null) {
            CountText(countIfAdded, style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = colors.textPrimary)
        }
        Box(Modifier.widthIn(min = Size.minTarget), contentAlignment = Alignment.CenterEnd) {
            Text(
                formatCount(libraryTotal),
                style = CrateType.footnote.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                color = colors.textTertiary,
            )
        }
    }
}

/** Bold on the first case- and diacritic-insensitive match of [query] in [name]. */
private fun highlight(name: String, query: String) = buildAnnotatedString {
    if (query.isEmpty()) { append(name); return@buildAnnotatedString }
    fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    // Map folded indices back to the original: fold char by char so lengths stay aligned.
    val folded = StringBuilder()
    val origin = ArrayList<Int>()
    name.forEachIndexed { i, c -> fold(c.toString()).forEach { f -> folded.append(f); origin.add(i) } }
    val at = folded.indexOf(fold(query))
    if (at < 0) { append(name); return@buildAnnotatedString }
    val start = origin[at]
    val end = origin[at + fold(query).length - 1] + 1
    append(name.substring(0, start))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name.substring(start, end)) }
    append(name.substring(end))
}

/** The row shown when a typed term matches no tag: ⌗ · "No tracks tagged “x”". */
@Composable
fun TagUnknownRow(term: String, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    val text = "No tracks tagged “$term”"
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = text }
            .heightIn(min = Size.minTarget)
            .padding(horizontal = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tag, null, Modifier.size(14.dp), tint = colors.textTertiary)
        Text(text, Modifier.weight(1f), style = CrateType.secondary, color = colors.textSecondary)
    }
}
