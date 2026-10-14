package com.overmap.crate.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.overmap.crate.app.SharedPublisher
import com.overmap.crate.core.SharedPreset
import com.overmap.crate.ui.components.PresetTile
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.PresetColor
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.crateIcon
import kotlinx.coroutines.launch

/**
 * "Choose preset" for the small widget (Swift: `SelectPresetIntent`): shown when the widget is added and when it
 * is reconfigured. Lists every preset, "In widget" ones first.
 */
class PresetConfigActivity : ComponentActivity() {
    private var widgetID = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetID = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, result())
        if (widgetID == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        val presets = SharedPublisher.store(this).loadSnapshot()?.presets.orEmpty()
            .let { all -> all.filter { it.inWidget } + all.filterNot { it.inWidget } }
        val glanceID = GlanceAppWidgetManager(this).getGlanceIdBy(widgetID)
        lifecycleScope.launch {
            val current = runCatching {
                getAppWidgetState(this@PresetConfigActivity, PreferencesGlanceStateDefinition, glanceID)[PresetWidget.presetKey]
            }.getOrNull()
            setContent {
                CrateTheme {
                    ChoosePreset(presets, current ?: presets.firstOrNull()?.id, onPick = { pick(it) })
                }
            }
        }
    }

    private fun result() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetID)

    private fun pick(preset: SharedPreset) {
        lifecycleScope.launch {
            val glanceID = GlanceAppWidgetManager(this@PresetConfigActivity).getGlanceIdBy(widgetID)
            updateAppWidgetState(this@PresetConfigActivity, glanceID) { it[PresetWidget.presetKey] = preset.id }
            PresetWidget().update(this@PresetConfigActivity, glanceID)
            setResult(RESULT_OK, result())
            finish()
        }
    }
}

@Composable
private fun ChoosePreset(presets: List<SharedPreset>, selected: String?, onPick: (SharedPreset) -> Unit) {
    val colors = CrateTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.bgBase).safeDrawingPadding().padding(horizontal = Space.screenMargin),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Spacer(Modifier.size(Space.l))
        Text("Choose preset", style = CrateType.sheetTitle, color = colors.textPrimary)
        Text("The preset this widget sends to Now.", style = CrateType.secondary, color = colors.textSecondary)
        Spacer(Modifier.size(Space.s))
        if (presets.isEmpty()) {
            Text("Open Crate once so the widget can load your presets.", Modifier.fillMaxWidth().crateCard().padding(Space.l),
                 style = CrateType.secondary, color = colors.textSecondary)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(presets, key = { it.id }) { p ->
                val isSelected = p.id == selected
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Size.minTarget).crateCard()
                        .semantics(mergeDescendants = true) { this.selected = isSelected }
                        .clickable(role = Role.RadioButton) { onPick(p) }
                        .padding(horizontal = Space.l, vertical = Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresetTile(PresetColor.of(p.color), p.icon, size = 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = CrateType.headline, color = colors.textPrimary, maxLines = 1)
                        Text(p.summary, style = CrateType.footnote, color = colors.textSecondary, maxLines = 1)
                    }
                    if (isSelected) Icon(crateIcon("checkmark"), null, Modifier.size(18.dp), tint = colors.textPrimary)
                }
            }
        }
    }
}
