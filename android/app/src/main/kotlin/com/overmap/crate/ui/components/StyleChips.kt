package com.overmap.crate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.StyleShade
import com.overmap.crate.ui.theme.familyColor
import com.overmap.crate.ui.theme.familyOutline
import com.overmap.crate.ui.theme.onData

// region Style chip

/** How a [StyleChip] is drawn. */
@Immutable
sealed interface StyleChipVariant {
    /** Picked: family shade fill, black text. */
    data object Selected : StyleChipVariant

    /** Not picked: `fillControl`, hue dot and optional track count. */
    data class Off(val count: Int? = null) : StyleChipVariant

    /** Removed from a track (Edit tags): 1.5 dp family outline, "− Tech House  Undo". */
    data object Removed : StyleChipVariant
}

/**
 * A style (or genre) chip, always "Style · Genre" (design principle 6).
 *
 * @param style Style display name ("Deep House").
 * @param family The family whose hue the chip takes.
 * @param genre Parent shown after a "·" ("House"); `null` hides it.
 * @param shade Which of the family's three shades (see the `canonical` overload).
 * @param variant Selected, off or removed.
 * @param edited Shows a pencil after the name (style added by the user).
 * @param onClick Tap (opens the picker at this genre). `null` makes the chip static.
 * @param onRemove Adds a trailing × that removes the chip (and a TalkBack "Remove" action).
 * @param onUndo The Undo action of the removed variant.
 */
@Composable
fun StyleChip(
    style: String,
    family: Family,
    modifier: Modifier = Modifier,
    genre: String? = null,
    shade: StyleShade = StyleShade.BASE,
    variant: StyleChipVariant = StyleChipVariant.Selected,
    edited: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
) {
    val colors = CrateTheme.colors
    val dark = colors.isDark
    val pressedOverlay = if (variant is StyleChipVariant.Off) colors.fillControlActive.copy(alpha = 0.6f) else colors.onData.copy(alpha = 0.08f)
    val label = buildList {
        add(style)
        genre?.let(::add)
        if (edited) add("edited")
        when (variant) {
            is StyleChipVariant.Off -> variant.count?.let { add("$it tracks") }
            StyleChipVariant.Removed -> add("removed")
            else -> {}
        }
    }.joinToString(", ")

    ChipPressable(
        onClick = onClick,
        pressedOverlay = pressedOverlay,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = label
            if (variant == StyleChipVariant.Selected) selected = true
            if (onClick != null) button(onClick)
            chipActions(onRemove = onRemove, onUndo = onUndo)
        },
    ) {
        when (variant) {
            StyleChipVariant.Selected -> ChipChrome(familyColor(family, shade, dark), colors.onData) {
                ChipText(style)
                if (genre != null) ChipText("· $genre", style = CrateType.chipContext, modifier = Modifier.alpha(0.7f))
                if (edited) Icon(Icons.Filled.Edit, null, Modifier.size(12.dp))
                if (onRemove != null) RemoveGlyph(onRemove)
            }
            is StyleChipVariant.Off -> ChipChrome(colors.fillControl, colors.textPrimary, spacing = Space.chipGap) {
                Box(Modifier.size(Metric.styleDot).background(familyColor(family, StyleShade.BASE, dark), CircleShape))
                ChipText(style)
                variant.count?.let {
                    CountText(it, style = CrateType.footnote.copy(fontWeight = FontWeight.Medium), color = colors.textSecondary)
                }
            }
            StyleChipVariant.Removed -> {
                val outline = familyOutline(family, dark)
                ChipChrome(Color.Transparent, outline, outline = outline) {
                    ChipText("− $style")
                    if (onUndo != null) {
                        Text(
                            "Undo",
                            style = CrateType.chipLabel.copy(fontWeight = FontWeight.Normal, textDecoration = TextDecoration.Underline),
                            modifier = Modifier.clickable(onClick = onUndo),
                        )
                    }
                }
            }
        }
    }
}

/** Same, with the shade picked by `StyleShade.of(canonical)` (stable FNV-1a, identical to iOS). */
@Composable
fun StyleChip(
    style: String,
    canonical: String,
    family: Family,
    modifier: Modifier = Modifier,
    genre: String? = null,
    variant: StyleChipVariant = StyleChipVariant.Selected,
    edited: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
) = StyleChip(
    style = style, family = family, modifier = modifier, genre = genre, shade = StyleShade.of(canonical),
    variant = variant, edited = edited, onClick = onClick, onRemove = onRemove, onUndo = onUndo,
)

/** The small trailing × inside a chip (its own tap target; TalkBack uses the chip's "Remove" action). */
@Composable
internal fun RemoveGlyph(onRemove: () -> Unit) {
    Box(
        Modifier.heightIn(min = 16.dp).padding(start = 1.dp).clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, null, Modifier.size(13.dp).alpha(0.6f), tint = LocalContentColor.current)
    }
}

/** Marks the node as a button whose TalkBack activation calls [action]. */
internal fun SemanticsPropertyReceiver.button(action: () -> Unit) {
    role = Role.Button
    onClick(label = null) { action(); true }
}

internal fun SemanticsPropertyReceiver.chipActions(onRemove: (() -> Unit)? = null, onUndo: (() -> Unit)? = null, onEdit: Pair<String, () -> Unit>? = null) {
    val actions = buildList {
        onRemove?.let { add(CustomAccessibilityAction("Remove") { it(); true }) }
        onUndo?.let { add(CustomAccessibilityAction("Undo") { it(); true }) }
        onEdit?.let { (name, action) -> add(CustomAccessibilityAction(name) { action(); true }) }
    }
    if (actions.isNotEmpty()) customActions = actions
}

// endregion

// region Genre summary

/** How a picked genre's ticked styles are summarised. */
@Immutable
sealed interface GenreSummary {
    /** Chip text after the "·". */
    val text: String

    /** Every style ticked. */
    data object AllStyles : GenreSummary {
        override val text: String get() = "all styles"
    }

    /** All but these (1–3) ticked. */
    data class Except(val names: List<String>) : GenreSummary {
        override val text: String get() = "except " + names.joinToString(", ")
    }

    /** [ticked] of [total] ticked. */
    data class Count(val ticked: Int, val total: Int) : GenreSummary {
        override val text: String get() = "$ticked of $total styles"
    }

    /** 1–3 ticked: show these as separate style chips. */
    data class Styles(val names: List<String>) : GenreSummary {
        override val text: String get() = names.joinToString(", ")
    }

    companion object {
        /**
         * The design-system rule: all → [AllStyles]; 1–3 unticked → [Except]; 1–3 ticked → [Styles]; otherwise
         * [Count]. [all] is the genre's styles in display order.
         */
        fun make(ticked: Set<String>, all: List<String>): GenreSummary {
            val unticked = all.filter { it !in ticked }
            val tickedInOrder = all.filter { it in ticked }
            return when {
                unticked.isEmpty() -> AllStyles
                unticked.size <= 3 -> Except(unticked)
                tickedInOrder.size in 1..3 -> Styles(tickedInOrder)
                else -> Count(tickedInOrder.size, all.size)
            }
        }
    }
}

/**
 * One chip summarising a picked genre's ticked styles: "House · all styles", "House · except Garage House",
 * "Electronic · 24 of 61 styles". Family base fill, black text. For 1–3 ticked styles show one [StyleChip] each
 * ([GenreSummary.make] decides).
 */
@Composable
fun GenreSummaryChip(
    family: Family,
    summary: GenreSummary,
    modifier: Modifier = Modifier,
    name: String? = null,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = CrateTheme.colors
    val title = name ?: family.displayName
    ChipPressable(
        onClick = onClick,
        pressedOverlay = colors.onData.copy(alpha = 0.08f),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$title, ${summary.text}"
            selected = true
            if (onClick != null) button(onClick)
            chipActions(onRemove = onRemove)
        },
    ) {
        ChipChrome(familyColor(family, StyleShade.BASE, colors.isDark), colors.onData) {
            ChipText(title)
            ChipText("· ${summary.text}", style = CrateType.chipContext, modifier = Modifier.alpha(0.7f))
            if (onRemove != null) RemoveGlyph(onRemove)
        }
    }
}

// endregion

// region Family mini chip / dot / add chip

/** The small 20 dp family chip (Weekly rows, vote tables, vibe previews): family base fill, radius 5, 12 sp semibold black. */
@Composable
fun FamilyMiniChip(family: Family, modifier: Modifier = Modifier, title: String? = null) {
    val colors = CrateTheme.colors
    val text = title ?: family.displayName
    Box(
        modifier
            .clearAndSetSemantics { contentDescription = "Genre $text" }
            .heightIn(min = Metric.miniChipHeight)
            .background(familyColor(family, StyleShade.BASE, colors.isDark), RoundedCornerShape(Radius.badge))
            .padding(horizontal = Metric.miniChipPaddingH),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = CrateType.footnote.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = colors.onData, maxLines = 1)
    }
}

/** The hue dot: 10 dp in picker rows ([large]), 8 dp in chips. Hidden from TalkBack. */
@Composable
fun FamilyDot(family: Family, modifier: Modifier = Modifier, shade: StyleShade = StyleShade.BASE, large: Boolean = true) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(if (large) Metric.familyDot else Metric.styleDot)
            .background(familyColor(family, shade, CrateTheme.colors.isDark), CircleShape),
    )
}

/** A plain `fillControl` chip with a "＋" title: "＋ Style", "＋ Add", "＋ New vibe". */
@Composable
fun AddChip(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    ChipPressable(
        onClick = onClick,
        pressedOverlay = colors.fillControlActive.copy(alpha = 0.6f),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = title
            button(onClick)
        },
    ) {
        ChipChrome(colors.fillControl, colors.textPrimary, spacing = Space.xs) {
            Icon(Icons.Filled.Add, null, Modifier.size(14.dp))
            ChipText(title)
        }
    }
}

// endregion
