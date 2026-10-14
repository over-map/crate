package com.overmap.crate.screens.build

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.overmap.crate.core.Camelot
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.CamelotBadge
import com.overmap.crate.ui.components.CamelotBadgeStyle
import com.overmap.crate.ui.components.CamelotWheel
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Camelot key picker sheet (2i): the port of iOS `KeyPickerView`.
 *
 * Edits a draft key + compatible flag; Done commits to `model.build`, "Any key" clears the draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyPickerSheet(onDismiss: () -> Unit) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(model.build.key) }
    var compatible by remember { mutableStateOf(model.build.compatible) }

    fun close(commit: Boolean) {
        if (commit) model.updateBuild { it.copy(key = key, compatible = compatible) }
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgSheet,
        contentColor = colors.textPrimary,
    ) {
        Column(Modifier.fillMaxSize()) {
            SheetHeader(
                title = "Key",
                onCancel = { close(false) },
                actionTitle = "Any key",
                actionEnabled = key != null,
                onAction = { key = null },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.screenMargin).padding(bottom = Space.l),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                CamelotWheel(
                    selection = key,
                    onSelectionChange = { key = it },
                    compatible = compatible,
                    modifier = Modifier.padding(top = Space.s).widthIn(max = 340.dp).fillMaxWidth(),
                )
                Text(
                    "INNER A · MINOR    OUTER B · MAJOR", style = CrateType.panelLabel, color = colors.textTertiary,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                KeyCard(key, compatible, onCompatibleChange = { compatible = it })
            }
            val count = model.count { it.copy(key = key, compatible = compatible) }
            SheetBottomBar(count = count, caption = "match all filters", onDone = { close(true) })
        }
    }
}

@Composable
private fun KeyCard(key: String?, compatible: Boolean, onCompatibleChange: (Boolean) -> Unit) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    Column(Modifier.fillMaxWidth().crateCard()) {
        BuildSwitch(
            checked = compatible, onCheckedChange = onCompatibleChange,
            modifier = Modifier.fillMaxWidth().padding(Space.m),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                Text("Include compatible keys", style = CrateType.body, color = colors.textPrimary)
                Text(compatibleNote(key), style = CrateType.footnote, color = colors.textSecondary)
            }
        }
        if (key != null) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
            val (exact, compat) = model.keyCounts(key)
            val exactRow: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    CamelotBadge(code = key, size = CamelotBadgeStyle.SMALL)
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) { append(fmt(exact)) }
                            withStyle(SpanStyle(color = colors.textSecondary)) { append(" tracks") }
                        },
                        style = CrateType.secondary,
                    )
                }
            }
            val compatRow: @Composable () -> Unit = {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.textSecondary)) { append("with compatible ") }
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (compatible) colors.textPrimary else colors.textSecondary)) {
                            append(fmt(compat))
                        }
                    },
                    style = CrateType.secondary,
                )
            }
            val a11y = Modifier.clearAndSetSemantics { contentDescription = "$key: $exact tracks, with compatible keys: $compat" }
            if (CrateTheme.isAccessibilitySize) {
                Column(
                    a11y.fillMaxWidth().heightIn(min = Size.minTarget).padding(horizontal = Space.m, vertical = Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) { exactRow(); compatRow() }
            } else {
                Row(
                    a11y.fillMaxWidth().heightIn(min = Size.minTarget).padding(horizontal = Space.m, vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) { exactRow(); Spacer(Modifier.weight(1f)); compatRow() }
            }
        }
    }
}

private fun compatibleNote(key: String?): String {
    val k = key?.let { Camelot(it) } ?: return "Neighbouring keys and the relative key mix smoothly"
    val others = k.compatible.drop(1).map { it.code }
    val list = others.dropLast(1).joinToString(", ") + " and " + (others.lastOrNull() ?: "")
    return "$list mix smoothly with ${k.code}"
}
