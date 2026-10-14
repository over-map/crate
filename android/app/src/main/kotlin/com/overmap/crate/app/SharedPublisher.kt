package com.overmap.crate.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.overmap.crate.MainActivity
import com.overmap.crate.core.CrateLink
import com.overmap.crate.core.Preset
import com.overmap.crate.core.PresetSnapshot
import com.overmap.crate.core.Query
import com.overmap.crate.core.SharedPreset
import com.overmap.crate.core.SharedStore
import com.overmap.crate.core.StyleLabel
import com.overmap.crate.ui.theme.PresetColor
import com.overmap.crate.widget.Widgets
import java.io.File

/**
 * What the widget and the launcher shortcuts read, written whenever the library, overrides, vibes or presets
 * change: each preset's match set and display lines in `files/Shared/presets.json` (same format as the iOS App
 * Group file), and one launcher shortcut per preset (long-press the Crate icon → "Send Dark 138+").
 */
object SharedPublisher {
    /** Launcher shortcuts shown (Android shows about four in the long-press menu). */
    private const val MAX_SHORTCUTS = 4

    fun store(context: Context) = SharedStore(File(context.filesDir, "Shared"))

    fun publish(context: Context, model: AppModel) {
        if (model.index.tracks.isEmpty()) return
        val store = store(context)
        val presets = model.snapshot.presets.presets.map { p ->
            val uris = model.index.matchingIDs(p.query).mapNotNull { id -> model.index.tracks[id]?.spotifyURI(id) }
            val (summary, detail) = lines(p.query, model)
            SharedPreset(id = p.id, name = p.name, icon = p.icon, color = p.color, inWidget = p.inWidget,
                         summary = summary, detail = detail, uris = uris, limit = p.query.limit)
        }
        val next = PresetSnapshot(nowPlaylistID = model.nowPlaylistID, presets = presets)
        val old = store.loadSnapshot()
        if (old == null || old.presets != next.presets || old.nowPlaylistID != next.nowPlaylistID) {
            store.save(next)
            Widgets.refresh(context)
        }
        shortcuts(context, model, model.snapshot.presets.presets)
    }

    /** Small widget: "Hard Techno, Breaks · 136–142". Rectangular lines: "136–142 · ϟ6–10". */
    fun lines(q: Query, model: AppModel): Pair<String, String> {
        val tax = model.snapshot.taxonomy
        val genre = buildList {
            q.familiesInclude.forEach { add(tax.displayName(it)) }
            q.familyStyles.forEach { (f, styles) ->
                if (styles.isNotEmpty()) add(styles.joinToString(", ") { StyleLabel(canonical = it, aliases = tax.aliases).styleDisplay })
                else add(tax.displayName(f))
            }
            if (q.stylesInclude.isNotEmpty()) add(q.stylesInclude.joinToString(", ") { StyleLabel(canonical = it, aliases = tax.aliases).styleDisplay })
            if (q.genresInclude.isNotEmpty()) add(q.genresInclude.joinToString(", ") { tax.aliases[it] ?: it })
        }.joinToString(", ")
        val vibes = q.vibesInclude.joinToString(" + ") { model.snapshot.vibes.vibes[it]?.name ?: it.replaceFirstChar(Char::uppercase) }
        val tags = if (q.tagsInclude.isEmpty()) "" else "⌗ " + q.tagsInclude.joinToString(" + ")
        val bpm = q.bpm?.let { "${it.min.toInt()}–${if (it.max >= 1000) "∞" else it.max.toInt().toString()}" + if (it.halfDouble) " ×½" else "" } ?: ""
        val energy = q.energy?.takeUnless { it.min <= 1 && it.max >= 10 }?.let { "ϟ" + if (it.min == it.max) "${it.min}" else "${it.min}–${it.max}" } ?: ""
        val summary = listOf(genre, vibes, tags, bpm).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Whole library" }
        val detail = listOf(bpm, energy).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { summary }
        return summary to detail
    }

    private fun shortcuts(context: Context, model: AppModel, presets: List<Preset>) {
        val ordered = presets.filter { it.inWidget } + presets.filterNot { it.inWidget }
        val list = ordered.take(MAX_SHORTCUTS).mapIndexed { rank, p ->
            ShortcutInfoCompat.Builder(context, "preset-${p.id}")
                .setShortLabel(p.name.take(24))
                .setLongLabel("Send ${p.name} to Now")
                .setIcon(IconCompat.createWithBitmap(tile(PresetColor.of(p.color))))
                .setRank(rank)
                .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse(CrateLink.send(p.id)), context, MainActivity::class.java)
                    .putExtra(MainActivity.LINK_KEY_EXTRA, model.linkKey))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, list) }
    }

    /** A round preset-colour tile for the shortcut icon. */
    private fun tile(color: PresetColor): Bitmap {
        val size = 108
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.argb(255,
            (color.tile().red * 255).toInt(), (color.tile().green * 255).toInt(), (color.tile().blue * 255).toInt()) }
        Canvas(bmp).drawRoundRect(RectF(18f, 18f, 90f, 90f), 18f, 18f, paint)
        return bmp
    }
}
