package com.overmap.crate.screens.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space

/** Section label row: mono label on the left, an optional trailing label or action on the right. */
@Composable
internal fun BuildSectionLabel(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val colors = CrateTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(), style = CrateType.panelLabel, color = colors.textSecondary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f).width(Space.s))
        trailing()
    }
}

/** A trailing panel label (`textSecondary`, like iOS panelLabelStyle), e.g. "COUNT IF ADDED". */
@Composable
internal fun TrailingPanelLabel(text: String, modifier: Modifier = Modifier, color: Color = CrateTheme.colors.textSecondary) {
    Text(text.uppercase(), modifier, style = CrateType.panelLabel, color = color, maxLines = 1)
}

/** Lets a horizontally scrolling row run under the screen margins (like iOS's negative padding). */
internal fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** The iOS-style segmented control: `fillControl` track, `fillControlActive` selection, 44 dp tall. */
@Composable
internal fun BuildSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String> = options,
) {
    val colors = CrateTheme.colors
    Row(
        modifier
            .heightIn(min = Size.minTarget)
            .background(colors.fillControl, RoundedCornerShape(Radius.control + Space.xxs))
            .padding(Space.xxs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, title ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = Size.minTarget - Space.xs)
                    .background(if (on) colors.fillControlActive else Color.Transparent, RoundedCornerShape(Radius.control))
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title, style = if (on) CrateType.chipLabel else CrateType.secondary, color = colors.textPrimary,
                    maxLines = 1, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Space.s).clearAndSetSemantics { contentDescription = labels[i] },
                )
            }
        }
    }
}

/** A labelled switch row ("Half/double too", "Include compatible keys"); the whole row toggles. */
@Composable
internal fun BuildSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: @Composable RowScope.() -> Unit) {
    val colors = CrateTheme.colors
    Row(
        modifier
            .heightIn(min = Size.minTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        label()
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent, checkedBorderColor = colors.accent,
                uncheckedTrackColor = colors.fillControl, uncheckedThumbColor = colors.textTertiary,
                uncheckedBorderColor = colors.textTertiary,
            ),
        )
    }
}

/** Bottom bar of the picker sheets: live count + caption on the left, Done on the right. */
@Composable
internal fun SheetBottomBar(count: Int, caption: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    Column(modifier.fillMaxWidth().background(colors.bgCard)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.screenMargin, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                com.overmap.crate.ui.components.CountText(count, style = CrateType.numeralMedium, color = colors.textPrimary)
                Text(caption, style = CrateType.footnote, color = colors.textSecondary, maxLines = 2)
            }
            Box(
                Modifier
                    .heightIn(min = Size.buttonHeight)
                    .background(colors.accent, RoundedCornerShape(Radius.button))
                    .clickable(role = Role.Button, onClick = onDone)
                    .padding(horizontal = Space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text("Done", style = CrateType.headline, color = colors.onAccent)
            }
        }
    }
}
