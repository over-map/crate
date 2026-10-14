package com.overmap.crate.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Debug only: asks the launcher to pin a widget (the system shows its "Add to home screen" dialog).
 * `adb shell am start -n com.overmap.crate/.widget.PinWidgetActivity --es kind preset|grid`
 * With `--ez refresh true` it only redraws the placed widgets (after writing `files/Shared/status.json` by hand).
 */
class PinWidgetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            if (intent.getBooleanExtra("refresh", false)) {
                Widgets.update(applicationContext)
                finish()
                return@launch
            }
            val manager = GlanceAppWidgetManager(this@PinWidgetActivity)
            if (intent.getStringExtra("kind") == "grid") manager.requestPinGlanceAppWidget(GridWidgetReceiver::class.java)
            else manager.requestPinGlanceAppWidget(PresetWidgetReceiver::class.java)
            finish()
        }
    }
}
