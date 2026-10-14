package com.overmap.crate.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space

/** A tab's large title ("Daily", "Library") with optional trailing actions. */
@Composable
fun LargeTitleHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = Space.screenMargin, end = Space.xs, top = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = CrateType.screenTitle, color = CrateTheme.colors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions()
    }
}

/** A pushed screen's bar: back arrow, title, trailing actions. */
@Composable
fun PushedTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = CrateTheme.colors.textPrimary)
        }
        Text(
            title, style = CrateType.headline.copy(fontSize = CrateType.sheetTitle.fontSize),
            color = CrateTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Space.xs).semantics { heading() },
        )
        actions()
    }
}

/** A sheet's bar: Cancel · title · confirm (the iOS inline navigation bar). */
@Composable
fun SheetTopBar(
    title: String,
    onCancel: () -> Unit,
    confirmTitle: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit = {},
) {
    val c = CrateTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = Size.minTarget + Space.s).padding(horizontal = Space.xs)) {
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) {
            Text("Cancel", style = CrateType.body, color = c.textPrimary)
        }
        Text(
            title, style = CrateType.headline, color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).semantics { heading() },
        )
        if (confirmTitle != null) {
            TextButton(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(
                    confirmTitle, style = CrateType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (confirmEnabled) c.textPrimary else c.textTertiary,
                )
            }
        }
    }
}

/** Hairline separator between rows. */
@Composable
fun RowSeparator(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Spacer(
        modifier.fillMaxWidth().height(0.5.dp).background(CrateTheme.colors.separator),
    )
}
