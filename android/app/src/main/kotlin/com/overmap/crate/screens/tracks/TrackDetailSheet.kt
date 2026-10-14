package com.overmap.crate.screens.tracks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.AppModel
import com.overmap.crate.core.Camelot
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.screens.vibes.VibeEditorSheet
import com.overmap.crate.ui.components.BPMText
import com.overmap.crate.ui.components.CamelotBadge
import com.overmap.crate.ui.components.CamelotBadgeStyle
import com.overmap.crate.ui.components.ChipFlow
import com.overmap.crate.ui.components.ConfidenceCopy
import com.overmap.crate.ui.components.ConfidenceLine
import com.overmap.crate.ui.components.CoverImage
import com.overmap.crate.ui.components.PanelLabel
import com.overmap.crate.ui.components.StyleChip
import com.overmap.crate.ui.components.VibeChip
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.energyColor
import com.overmap.crate.ui.theme.familyColor
import kotlinx.coroutines.launch

/**
 * Track detail (design 6m–6n) as a bottom sheet: header, styles + confidence, BPM / key / energy, vibes, community
 * tags, actions. Top bar: Close / Edit (opens [EditTrackSheet]). Vibe chips open the vibe editor.
 */
@Composable
fun TrackDetailSheet(trackID: String, onDismiss: () -> Unit) {
    // Debug screenshots: the first sheet opened by a crate://track link with `--es screen …` shows the hooked state.
    val debugScreen = remember { TracksDebug.take() }
    if (debugScreen != null) {
        TracksDebugRoot(debugScreen, onDismiss)
        return
    }
    TrackDetailSheet(trackID, onDismiss, startsExpanded = false)
}

/** [startsExpanded] (debug/screenshots) opens with the confidence table and the community tags expanded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailSheet(trackID: String, onDismiss: () -> Unit, startsExpanded: Boolean) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = CrateTheme.colors.bgSheet,
        dragHandle = null,
    ) {
        TrackDetailContent(trackID, onClose = close, startsExpanded = startsExpanded)
    }
}

/** Sheet chrome shared by the track sheets: leading / trailing text buttons and an optional centred title. */
@Composable
internal fun SheetTopBar(
    leading: String,
    onLeading: () -> Unit,
    trailing: String?,
    onTrailing: () -> Unit,
    title: String? = null,
    trailingEnabled: Boolean = true,
    trailingBold: Boolean = false,
    trailingLabel: String? = null,
) {
    val colors = CrateTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs)) {
        TextButton(onClick = onLeading, modifier = Modifier.align(Alignment.CenterStart)) {
            Text(leading, style = CrateType.body, color = colors.textPrimary)
        }
        if (title != null) {
            Text(
                title, style = CrateType.headline, color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
            )
        }
        if (trailing != null) {
            TextButton(
                onClick = onTrailing, enabled = trailingEnabled,
                modifier = Modifier.align(Alignment.CenterEnd).let { m ->
                    if (trailingLabel != null) m.semantics { contentDescription = trailingLabel } else m
                },
            ) {
                Text(
                    trailing,
                    style = if (trailingBold) CrateType.headline else CrateType.body,
                    color = if (trailingEnabled) colors.textPrimary else colors.textTertiary,
                )
            }
        }
    }
}

private sealed interface DetailSheet {
    data object Edit : DetailSheet
    data class Vibe(val id: String) : DetailSheet
}

@Composable
private fun TrackDetailContent(trackID: String, onClose: () -> Unit, startsExpanded: Boolean) {
    val model = rememberModel()
    var sheet by remember { mutableStateOf<DetailSheet?>(null) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        SheetTopBar("Close", onClose, "Edit", { sheet = DetailSheet.Edit }, trailingLabel = "Edit tags and vibes")
        val info = TrackInfo.of(trackID, model)
        if (info == null) {
            EmptyState("Track not in library")
        } else {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.screenMargin).padding(bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
            ) {
                Header(info)
                Styles(info, startsExpanded)
                if (info.isAnalysed) {
                    Numbers(info)
                } else {
                    Text("No preview · tag by hand", style = CrateType.secondary, color = CrateTheme.colors.textSecondary)
                }
                Vibes(model, trackID) { sheet = DetailSheet.Vibe(it) }
                CommunityTags(info, startsExpanded)
                Actions(model, info)
            }
        }
    }
    when (val s = sheet) {
        DetailSheet.Edit -> EditTrackSheet(trackID, onDismiss = { sheet = null })
        is DetailSheet.Vibe -> VibeEditorSheet(vibeID = s.id, seedTrackID = null, onDismiss = { sheet = null })
        null -> {}
    }
}

@Composable
internal fun EmptyState(text: String) {
    val colors = CrateTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.xxl * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(Icons.Filled.MusicNote, null, Modifier.size(40.dp), tint = colors.textTertiary)
        Text(text, style = CrateType.headline, color = colors.textPrimary)
    }
}

// Header

@Composable
private fun Header(info: TrackInfo) {
    val colors = CrateTheme.colors
    val texts: @Composable () -> Unit = {
        Column(
            Modifier.semantics(mergeDescendants = true) { heading() },
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text(info.title, style = CrateType.sheetTitle, color = colors.textPrimary)
            Text(info.artist, style = CrateType.body, color = colors.textSecondary)
            Text(info.addedText, style = CrateType.footnote, color = colors.textTertiary)
        }
    }
    val cover: @Composable () -> Unit = {
        CoverImage(info.coverURL, size = 96.dp, radius = Radius.coverLarge, tint = familyColor(info.family, dark = colors.isDark))
    }
    if (CrateTheme.isAccessibilitySize) {
        Column(Modifier.padding(top = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) { cover(); texts() }
    } else {
        Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
            cover(); texts()
        }
    }
}

// Styles + confidence

@Composable
private fun Styles(info: TrackInfo, startsExpanded: Boolean) {
    val colors = CrateTheme.colors
    val all = info.displayStyles
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (all.isNotEmpty()) {
            ChipFlow {
                all.forEachIndexed { i, s ->
                    StyleChip(
                        style = info.styleName(s), canonical = s, family = info.family,
                        genre = if (i == 0) info.familyName(info.family) else null, edited = info.isStyleEdited(s),
                    )
                }
            }
        } else if (info.family != Family.NONE) {
            ChipFlow { StyleChip(style = info.familyName(info.family), family = info.family, edited = info.overrideFamily != null) }
        }
        val summary = info.confidence
        if (summary != null) {
            key(summary.copy + summary.family.data) { ConfidenceLine(summary, expanded = startsExpanded) }
        } else if (info.overrideFamily != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Edit, null, Modifier.size(13.dp), tint = colors.textSecondary)
                Text(ConfidenceCopy.OVERRIDE, style = CrateType.footnote.copy(fontWeight = FontWeight.Medium), color = colors.textSecondary)
            }
        }
    }
}

// Numbers

@Composable
private fun Numbers(info: TrackInfo) {
    val colors = CrateTheme.colors
    val bpmCard: @Composable (Modifier) -> Unit = { m ->
        NumberCard("BPM", info.bpmEdited, m) {
            val bpm = info.bpm
            if (bpm != null) {
                BPMText(Math.round(bpm).toInt(), approx = info.bpmApprox, style = CrateType.numeralLarge, color = colors.textPrimary)
                if (info.bpmApprox) Text("Uncertain beat", style = CrateType.footnote, color = colors.textSecondary)
            } else UnknownValue()
        }
    }
    val keyCard: @Composable (Modifier) -> Unit = { m ->
        NumberCard("Key", info.keyEdited, m) {
            val k = info.key?.let { Camelot(it) }
            if (k != null) {
                CamelotBadge(k.code, size = CamelotBadgeStyle.MEDIUM)
                Text(k.name, style = CrateType.footnote, color = colors.textSecondary)
            } else UnknownValue()
        }
    }
    val energyCard: @Composable (Modifier) -> Unit = { m ->
        NumberCard("Energy", info.energyEdited, m) {
            val e = info.energy
            if (e != null) {
                Text("$e", style = CrateType.numeralLarge, color = colors.textPrimary)
                TrackEnergyBlocks(e)
            } else UnknownValue()
        }
    }
    if (CrateTheme.isAccessibilitySize) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            bpmCard(Modifier.fillMaxWidth()); keyCard(Modifier.fillMaxWidth()); energyCard(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            bpmCard(Modifier.weight(1f).fillMaxHeight())
            keyCard(Modifier.weight(1f).fillMaxHeight())
            energyCard(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun UnknownValue() {
    Text("—", style = CrateType.numeralLarge, color = CrateTheme.colors.textTertiary,
         modifier = Modifier.semantics { contentDescription = "Unknown" })
}

@Composable
private fun NumberCard(label: String, edited: Boolean, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.crateCard().padding(Space.m)
            .semantics(mergeDescendants = true) { if (edited) stateDescription = "edited" },
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            PanelLabel(label)
            if (edited) TrackEditedDot()
        }
        content()
    }
}

/** The 10 small energy blocks in the Energy number card. TalkBack reads "Energy 7 out of 10". */
@Composable
internal fun TrackEnergyBlocks(level: Int) {
    val colors = CrateTheme.colors
    Row(
        Modifier.widthIn(max = 96.dp).fillMaxWidth().clearAndSetSemantics { contentDescription = "Energy $level out of 10" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 1..10) {
            Box(
                Modifier.weight(1f).height(6.dp).background(
                    if (i <= level) energyColor(i, colors.isDark) else colors.fillControlActive, RoundedCornerShape(1.5.dp),
                ),
            )
        }
    }
}

// Vibes

@Composable
private fun Vibes(model: AppModel, trackID: String, open: (String) -> Unit) {
    val list = model.tracksVibes(trackID)
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        PanelLabel(if (list.isEmpty()) "Vibes" else "Vibes · in ${list.size}")
        if (list.isEmpty()) {
            Text("Not in any vibe yet. Add it in Edit.", style = CrateType.footnote, color = CrateTheme.colors.textSecondary)
        } else {
            ChipFlow {
                list.forEach { v ->
                    VibeChip(v.vibe.name, symbol = model.tracksVibeSymbol(v.id, v.vibe), count = null, onClick = { open(v.id) })
                }
            }
        }
    }
}

// Community tags

@Composable
private fun CommunityTags(info: TrackInfo, startsExpanded: Boolean) {
    val colors = CrateTheme.colors
    val tags = info.communityTags
    var expanded by rememberSaveable { mutableStateOf(startsExpanded) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val rowHeight = Size.minTarget + Space.xs
    when {
        tags.isEmpty() -> Row(
            Modifier.fillMaxWidth().crateCard().heightIn(min = rowHeight).padding(horizontal = Space.m)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Community tags", style = CrateType.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(if (info.track.tags == null) "Not looked up yet" else "None", style = CrateType.body, color = colors.textTertiary)
        }
        !expanded -> Row(
            Modifier.fillMaxWidth().crateCard().clickable { expanded = true }.heightIn(min = rowHeight)
                .padding(horizontal = Space.m)
                .clearAndSetSemantics {
                    contentDescription = "Community tags, ${tags.size}"
                    stateDescription = "Collapsed"
                    onClick("Shows the tags") { expanded = true; true }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text("Community tags", style = CrateType.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("${tags.size} · community", style = CrateType.body, color = colors.textSecondary)
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, Modifier.size(13.dp), tint = colors.textTertiary)
        }
        else -> Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable { expanded = false }
                    .clearAndSetSemantics {
                        contentDescription = "Community tags, ${tags.size}"
                        stateDescription = "Expanded"
                        onClick("Hides the tags") { expanded = false; true }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelLabel("Community tags · ${tags.size}", Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, null, Modifier.size(16.dp), tint = colors.textTertiary)
            }
            val shown = if (showAll) tags else tags.take(5)
            Column(Modifier.fillMaxWidth().crateCard()) {
                shown.forEachIndexed { i, tag ->
                    val from = info.tagSources(tag)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = Size.minTarget).padding(horizontal = Space.m)
                            .clearAndSetSemantics {
                                contentDescription = if (from.isEmpty()) "Tag $tag" else "Tag $tag, from " + listJoin(from.map { it.second })
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        Icon(Icons.Filled.Tag, null, Modifier.size(15.dp), tint = colors.textTertiary)
                        Text(tag, style = CrateType.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        Text(
                            if (from.isEmpty()) "COMMUNITY" else from.joinToString(" · ") { it.first },
                            style = CrateType.panelLabel, color = colors.textTertiary,
                        )
                    }
                    if (i < shown.size - 1 || tags.size > 5) HorizontalDivider(color = colors.separator)
                }
                if (tags.size > 5) {
                    Box(
                        Modifier.fillMaxWidth().clickable { showAll = !showAll }.heightIn(min = Size.minTarget)
                            .padding(horizontal = Space.m),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(if (showAll) "Show fewer" else "Show ${tags.size - 5} more", style = CrateType.footnote, color = colors.textSecondary)
                    }
                }
            }
            Text(
                "LFM Last.fm describes the artist, not this exact track · DSC Discogs, the release · BP Beatport, entered by you.",
                style = CrateType.footnote, color = colors.textSecondary,
            )
        }
    }
}

internal fun listJoin(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}

// Actions

@Composable
private fun Actions(model: AppModel, info: TrackInfo) {
    val colors = CrateTheme.colors
    val context = LocalContext.current
    var confirmUnlike by remember { mutableStateOf(false) }
    var unliked by rememberSaveable { mutableStateOf(false) }
    var unlikeError by remember { mutableStateOf<String?>(null) }
    var sentMoreLikeThis by rememberSaveable { mutableStateOf(false) }
    val start: () -> Unit = {
        sentMoreLikeThis = true
        model.scope.launch { model.sendMoreLikeThis(info.id) }
    }
    Column(Modifier.fillMaxWidth().crateCard()) {
        MoreLikeThisRow(model, info.id, sentMoreLikeThis, start)
        HorizontalDivider(color = colors.separator)
        ActionRow("Open in Spotify", Icons.Filled.NorthEast) { openSpotify(context, info.uri, info.id) }
        HorizontalDivider(color = colors.separator)
        ActionRow(
            if (unliked) "Removed from Liked Songs" else "Remove from Liked Songs", Icons.Filled.HeartBroken,
            disabled = unliked || model.isDemo,
        ) { confirmUnlike = true }
    }
    if (confirmUnlike) {
        AlertDialog(
            onDismissRequest = { confirmUnlike = false },
            title = { Text("Remove “${info.title}” from Liked Songs?") },
            text = { Text("It leaves Crate after tonight's sync. Your tag edits are kept in case you like it again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnlike = false
                    model.scope.launch {
                        try { model.spotify.unlike(info.uri); unliked = true } catch (_: Exception) {
                            unlikeError = "Spotify didn't accept it. Try again in a moment."
                        }
                    }
                }) { Text("Remove from Liked Songs", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnlike = false }) { Text("Cancel", color = colors.textPrimary) } },
            containerColor = colors.bgCard,
        )
    }
    unlikeError?.let { msg ->
        AlertDialog(
            onDismissRequest = { unlikeError = null },
            title = { Text("Couldn't remove the like") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { unlikeError = null }) { Text("OK", color = colors.textPrimary) } },
            containerColor = colors.bgCard,
        )
    }
}

private fun openSpotify(context: Context, uri: String, id: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/track/$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
private fun ActionRow(title: String, icon: ImageVector, disabled: Boolean = false, hint: String? = null, onClick: () -> Unit) {
    val colors = CrateTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !disabled, onClickLabel = hint, onClick = onClick)
            .heightIn(min = Size.minTarget + Space.xs).padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(title, style = CrateType.body, color = if (disabled) colors.textTertiary else colors.textPrimary, modifier = Modifier.weight(1f))
        Icon(icon, null, Modifier.size(15.dp), tint = colors.textTertiary)
    }
}

@Composable
private fun MoreLikeThisRow(model: AppModel, trackID: String, sent: Boolean, start: () -> Unit) {
    val colors = CrateTheme.colors
    val available = model.canSendMoreLikeThis(trackID)
    val idle: @Composable () -> Unit = {
        ActionRow(
            "More like this → Now", Icons.AutoMirrored.Filled.ArrowForwardIos, disabled = !available,
            hint = if (available) "Replace Now with 100 tracks that sound like this one" else null, onClick = start,
        )
    }
    if (!sent) { idle(); return }
    when (val s = model.sendState) {
        is AppModel.SendState.Sending -> Row(
            Modifier.fillMaxWidth().heightIn(min = Size.minTarget + Space.xs).padding(horizontal = Space.m)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            LinearProgressIndicator(
                progress = { s.done.toFloat() / maxOf(s.total, 1) }, modifier = Modifier.weight(1f),
                color = colors.accent, trackColor = colors.fillControlActive, drawStopIndicator = {},
            )
            Text("Sending… ${s.done}/${s.total}", style = CrateType.tagLine, color = colors.textSecondary)
        }
        is AppModel.SendState.Success -> Row(
            Modifier.fillMaxWidth().heightIn(min = Size.minTarget + Space.xs).padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(Icons.Filled.Check, null, Modifier.size(15.dp), tint = colors.success)
            Text("Sent ${s.count} tracks to Now", style = CrateType.footnote.copy(fontWeight = FontWeight.SemiBold),
                 color = colors.success, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.openNow() }) {
                Text("Listen", style = CrateType.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
            }
        }
        is AppModel.SendState.Error -> Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Retry", onClick = start)
                .heightIn(min = Size.minTarget + Space.xs).padding(Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(Icons.Filled.Close, null, Modifier.size(15.dp), tint = colors.error)
            Text(s.message, style = CrateType.footnote, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("Retry", style = CrateType.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
        }
        AppModel.SendState.Idle -> idle()
    }
}
