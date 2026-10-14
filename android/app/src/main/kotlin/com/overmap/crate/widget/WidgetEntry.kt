package com.overmap.crate.widget

import android.content.Context
import com.overmap.crate.app.SharedPublisher
import com.overmap.crate.core.PresetSnapshot
import com.overmap.crate.core.SendStatus
import com.overmap.crate.core.SharedPreset
import java.time.Instant

/** What a widget shows at one moment (Swift: `CrateEntry`). */
internal data class CrateEntry(
    val snapshot: PresetSnapshot?,
    /** The last send, if it should still show (Sent holds 30 s; an error stays). */
    val status: SendStatus?,
    /** Small widget: the configured preset (or the first widget preset). */
    val presetID: String?,
) {
    val preset: SharedPreset? get() = presetID?.let { snapshot?.preset(it) }

    /** The status, only if it concerns `id`. */
    fun status(forID: String?): SendStatus? = status?.takeIf { it.presetID == forID }

    companion object {
        /** Reads presets.json and status.json (Swift: `Timelines.load`). */
        fun load(context: Context, presetID: String?, now: Instant = Instant.now()): CrateEntry {
            val store = SharedPublisher.store(context)
            val snapshot = store.loadSnapshot()
            val id = presetID?.let { snapshot?.preset(it)?.id }
                ?: snapshot?.widgetPresets?.firstOrNull()?.id ?: snapshot?.presets?.firstOrNull()?.id
            return CrateEntry(snapshot, store.loadStatus()?.visible(now), id)
        }

        /** What the widget picker previews show. */
        val sample = CrateEntry(
            snapshot = PresetSnapshot(presets = listOf(
                SharedPreset(id = "dark", name = "Dark 138+", icon = "moon.fill", color = "violet", inWidget = true,
                             summary = "Hard Techno, Breaks · 136–142", detail = "136–142 · ϟ6–10"),
                SharedPreset(id = "italian", name = "Italian summer", icon = "sun.horizon.fill", color = "pink", inWidget = true,
                             summary = "Happy · ⌗ italian", detail = "Happy · ⌗ italian"),
                SharedPreset(id = "sunday", name = "Sunday 90–110", icon = "sun.max.fill", color = "amber", inWidget = true,
                             summary = "Downtempo · 90–110", detail = "90–110"),
                SharedPreset(id = "warehouse", name = "Warehouse", icon = "building.2.fill", color = "teal", inWidget = true,
                             summary = "Techno · Hypnotic · 126–134", detail = "126–134"),
            )),
            status = null,
            presetID = "dark",
        )
    }
}
