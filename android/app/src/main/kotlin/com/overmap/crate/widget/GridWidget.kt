package com.overmap.crate.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.overmap.crate.core.SendStatus
import com.overmap.crate.core.SharedPreset
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Space

/**
 * Medium home-screen widget (design 4a, 4c–4g): the first four "In widget" presets as a 2×2 grid. While one sends
 * the other cells dim and ignore taps; Sent shows on the tapped cell; an error strip runs along the bottom; empty
 * cells read "Add preset / in Crate".
 */
class GridWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(364.dp, 170.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            currentState(PresetWidget.revisionKey) // redraws when Widgets.update bumps it
            GridView(CrateEntry.load(context, presetID = null))
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GridView(CrateEntry.sample) }
    }
}

class GridWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GridWidget()
}

@Composable
internal fun GridView(entry: CrateEntry) {
    val context = LocalContext.current
    val presets = entry.snapshot?.widgetPresets.orEmpty()
    if (presets.isEmpty()) {
        EmptyWidget("Save a preset in Crate to send it from here",
                    surfaceModifier().clickable(openAppAction(context))
                        .semantics { contentDescription = "Save a preset in Crate to send it from here. Opens Crate." })
        return
    }
    val cells = (0 until 4).map { presets.getOrNull(it) }
    val failed = entry.status?.takeIf { it.phase == SendStatus.Phase.failed }
    Column(surfaceModifier().padding(Space.m)) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            Cell(cells[0], entry, GlanceModifier.defaultWeight().fillMaxHeight())
            Spacer(GlanceModifier.width(Space.s))
            Cell(cells[1], entry, GlanceModifier.defaultWeight().fillMaxHeight())
        }
        Spacer(GlanceModifier.height(Space.s))
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            Cell(cells[2], entry, GlanceModifier.defaultWeight().fillMaxHeight())
            Spacer(GlanceModifier.width(Space.s))
            Cell(cells[3], entry, GlanceModifier.defaultWeight().fillMaxHeight())
        }
        if (failed != null) {
            Spacer(GlanceModifier.height(Space.s))
            ErrorStrip(failed, entry)
        }
    }
}

@Composable
private fun Cell(p: SharedPreset?, entry: CrateEntry, modifier: GlanceModifier) {
    val context = LocalContext.current
    if (p == null) {
        CellBody(
            glyph = { Box(GlanceModifier.size(24.dp), contentAlignment = Alignment.Center) { Symbol("plus", 16.dp, crate { it.textSecondary }) } },
            title = "Add preset", line = "in Crate", tint = null, alpha = 1f,
            action = openAppAction(context), label = "Add preset in Crate", modifier = modifier,
        )
        return
    }
    val s = entry.status?.takeIf { it.presetID == p.id && it.phase != SendStatus.Phase.failed }
    val othersBusy = entry.status?.phase == SendStatus.Phase.sending && entry.status.presetID != p.id
    when (s?.phase) {
        SendStatus.Phase.sent -> CellBody(
            glyph = { StatusGlyph("checkmark", crate { it.success }, crate { it.successFill }, 24.dp) },
            title = "Sent · ${s.count ?: 0}", line = "Listen on Spotify", tint = crate { it.successFill }, alpha = 1f,
            action = openNowAction(context), label = "Sent ${s.count ?: 0} tracks. Open Spotify.", modifier = modifier,
        )
        SendStatus.Phase.sending -> CellBody(
            glyph = {
                Box(GlanceModifier.size(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(GlanceModifier.size(18.dp), color = crate { it.textSecondary })
                }
            },
            title = p.name, line = "Sending…", tint = null, alpha = 1f,
            action = null, label = "Sending ${p.name}", modifier = modifier,
        )
        else -> {
            val alpha = if (othersBusy) 0.4f else 1f
            CellBody(
                glyph = { WidgetPresetTile(p, 24.dp, alpha) },
                title = p.name, line = p.summary, tint = null, alpha = alpha,
                action = if (othersBusy) null else sendAction(p.id), label = "Send ${p.name} to Now", modifier = modifier,
            )
        }
    }
}

/** A cell: 1 dp strokeCard ring around the cell fill (Glance has no borders), glyph, name and line. */
@Composable
private fun CellBody(
    glyph: @Composable () -> Unit,
    title: String,
    line: String,
    tint: ColorProvider?,
    alpha: Float,
    action: Action?,
    label: String,
    modifier: GlanceModifier,
) {
    var outer = modifier.background(crate(alpha) { it.strokeCard }).cornerRadius(Radius.card).padding(1.dp)
        .semantics { contentDescription = label }
    if (action != null) outer = outer.clickable(action)
    Box(outer) {
        Row(
            GlanceModifier.fillMaxSize().background(tint ?: WidgetColors.cell(alpha))
                .cornerRadius(Radius.card - 1.dp).padding(horizontal = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            glyph()
            Spacer(GlanceModifier.width(Space.s))
            Column(GlanceModifier.defaultWeight()) {
                Text(title, style = WidgetType.text(15.sp, crate(alpha) { it.textPrimary }, FontWeight.Medium), maxLines = 1)
                Text(line, style = WidgetType.text(11.sp, crate(alpha) { it.textSecondary }), maxLines = 1)
            }
        }
    }
}

/** "✕ Spotify login expired · Dark 138+        Open Crate" on errorFill. */
@Composable
private fun ErrorStrip(s: SendStatus, entry: CrateEntry) {
    val context = LocalContext.current
    val name = entry.snapshot?.preset(s.presetID)?.name
    val title = s.failure?.title ?: "Not sent"
    Row(
        GlanceModifier.fillMaxWidth().height(24.dp).background(crate { it.errorFill }).cornerRadius(Radius.chip)
            .padding(horizontal = Space.s).clickable(openAppAction(context))
            .semantics { contentDescription = "$title${name?.let { ", $it" } ?: ""}. Open Crate." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("xmark.circle.fill", 12.dp, crate { it.error })
        Spacer(GlanceModifier.width(Space.xs))
        Text(title, style = WidgetType.text(12.sp, crate { it.textPrimary }), maxLines = 1)
        Text(name?.let { " · $it" } ?: "", style = WidgetType.text(12.sp, crate { it.textSecondary }), maxLines = 1,
             modifier = GlanceModifier.defaultWeight())
        Text("Open Crate", style = WidgetType.text(12.sp, crate { it.textPrimary }, FontWeight.Bold), maxLines = 1)
    }
}
