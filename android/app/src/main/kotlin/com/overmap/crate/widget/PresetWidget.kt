package com.overmap.crate.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import com.overmap.crate.core.SendStatus
import com.overmap.crate.core.SharedPreset
import com.overmap.crate.ui.theme.Space

/**
 * Small home-screen widget (design 4a, 4c–4g): one preset, picked in [PresetConfigActivity]. The whole widget is
 * the button: idle sends, Sending ignores taps, Sent opens Now in Spotify for 30 s, an error offers "Open Crate"
 * (login, missing data) or "Try again".
 */
class PresetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(170.dp, 170.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            currentState(revisionKey) // redraws when Widgets.update bumps it
            SmallView(CrateEntry.load(context, currentState(presetKey)))
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { SmallView(CrateEntry.sample) }
    }

    companion object {
        /** The configured preset ID (Glance per-widget state). */
        val presetKey = stringPreferencesKey("preset")
        /** Bumped on every refresh so the composition re-reads the shared files. */
        val revisionKey = intPreferencesKey("revision")
    }
}

class PresetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PresetWidget()
}

/** The widget's rounded surface. */
internal fun surfaceModifier(): GlanceModifier =
    GlanceModifier.fillMaxSize().appWidgetBackground().background(WidgetColors.surface())
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)

@Composable
internal fun SmallView(entry: CrateEntry) {
    val context = LocalContext.current
    val p = entry.preset
    if (p == null) {
        EmptyWidget("Save a preset in Crate to send it from here",
                    surfaceModifier().clickable(openAppAction(context))
                        .semantics { contentDescription = "Save a preset in Crate to send it from here. Opens Crate." })
        return
    }
    val status = entry.status(p.id)
    val action = when (status?.phase) {
        SendStatus.Phase.sending -> null
        SendStatus.Phase.sent -> openNowAction(context)
        SendStatus.Phase.failed -> if (needsApp(status.failure)) openAppAction(context) else sendAction(p.id)
        null -> sendAction(p.id)
    }
    var modifier = surfaceModifier().semantics { contentDescription = accessibilityLabel(p, status) }
    if (action != null) modifier = modifier.clickable(action)
    SmallCard(p, status, modifier)
}

@Composable
private fun SmallCard(p: SharedPreset, status: SendStatus?, modifier: GlanceModifier) {
    Column(modifier.padding(Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (status?.phase) {
                SendStatus.Phase.sent -> StatusGlyph("checkmark", crate { it.success }, crate { it.successFill })
                SendStatus.Phase.failed -> StatusGlyph("xmark", crate { it.error }, crate { it.errorFill })
                else -> WidgetPresetTile(p, 28.dp)
            }
            Spacer(GlanceModifier.width(Space.s))
            Text("CRATE", style = WidgetType.panelLabel(), maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(
            if (status?.phase == SendStatus.Phase.failed) status.failure?.title ?: "Not sent" else p.name,
            style = WidgetType.text(17.sp, crate { it.textPrimary }, FontWeight.Bold), maxLines = 2,
        )
        Text(line(p, status), style = WidgetType.text(12.sp, crate { it.textSecondary }), maxLines = 2,
             modifier = GlanceModifier.padding(top = Space.xxs))
        Spacer(GlanceModifier.defaultWeight())
        WidgetButtonLabel(
            text = buttonText(status),
            symbol = if (status?.phase == SendStatus.Phase.sent) "play.fill" else null,
            busy = status?.phase == SendStatus.Phase.sending,
            filled = status?.phase != SendStatus.Phase.failed,
        )
    }
}

private fun line(p: SharedPreset, s: SendStatus?): String = when (s?.phase) {
    SendStatus.Phase.sending -> "Filling Now…"
    SendStatus.Phase.sent -> "Sent · ${s.count ?: 0} tracks"
    SendStatus.Phase.failed -> "${p.name} wasn't sent"
    null -> p.summary
}

private fun buttonText(s: SendStatus?): String = when (s?.phase) {
    SendStatus.Phase.sending -> "Sending…"
    SendStatus.Phase.sent -> "Listen on Spotify"
    SendStatus.Phase.failed -> if (needsApp(s.failure)) "Open Crate" else "Try again"
    null -> "Send to Now"
}

/** TalkBack, mirroring the iOS VoiceOver labels. */
internal fun accessibilityLabel(p: SharedPreset, s: SendStatus?): String = when (s?.phase) {
    SendStatus.Phase.sending -> "Sending ${p.name}"
    SendStatus.Phase.sent -> "Sent ${s.count ?: 0} tracks. Open Spotify."
    SendStatus.Phase.failed -> "${p.name} wasn't sent. ${s.failure?.title ?: ""}. " +
        if (needsApp(s.failure)) "Open Crate." else "Try again."
    null -> "Send ${p.name} to Now"
}
