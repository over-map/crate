package com.overmap.crate.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.overmap.crate.core.SharedPreset
import com.overmap.crate.ui.theme.CrateColors
import com.overmap.crate.ui.theme.DarkCrateColors
import com.overmap.crate.ui.theme.LightCrateColors
import com.overmap.crate.ui.theme.PresetColor
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.crateIcon

// Glance can't use the Compose UI components, so the widget pieces (preset tile, status glyph, button label, empty
// state) are redrawn here with Glance primitives. Design: Crate Widgets 4a–4g.
// Dark: surface bgBase, cells bgCard. Light: surface bgCard, cells bgBase. Colours follow the system day/night.

/** Day / night colour from the Crate tokens, optionally faded (a dimmed medium-widget cell). */
internal fun crate(alpha: Float = 1f, pick: (CrateColors) -> Color): ColorProvider =
    ColorProvider(day = pick(LightCrateColors).fade(alpha), night = pick(DarkCrateColors).fade(alpha))

private fun Color.fade(alpha: Float) = if (alpha >= 1f) this else copy(alpha = this.alpha * alpha)

internal object WidgetColors {
    /** Widget background: bgBase (dark) / bgCard (light). */
    fun surface() = ColorProvider(day = LightCrateColors.bgCard, night = DarkCrateColors.bgBase)

    /** Medium-widget cell: bgCard (dark) / bgBase (light). */
    fun cell(alpha: Float = 1f) = ColorProvider(
        day = LightCrateColors.bgBase.fade(alpha), night = DarkCrateColors.bgCard.fade(alpha),
    )

    fun tile(color: PresetColor, alpha: Float = 1f) =
        ColorProvider(day = color.tile(dark = false).fade(alpha), night = color.tile(dark = true).fade(alpha))

    fun onTile(color: PresetColor, alpha: Float = 1f) =
        ColorProvider(day = color.onTile(dark = false).fade(alpha), night = color.onTile(dark = true).fade(alpha))
}

/** Type sizes of the iOS widget (headline 17, subheadline 15, caption 12, caption2 11, panel label 11 mono). */
internal object WidgetType {
    /**
     * Widgets have a fixed size, so text grows with the system font scale only up to [MAX_SCALE] (iOS widgets
     * likewise cap Dynamic Type); beyond that the button and lines would be cut off.
     */
    private const val MAX_SCALE = 1.3f

    private fun capped(size: TextUnit): TextUnit {
        val scale = android.content.res.Resources.getSystem().configuration.fontScale
        return if (scale <= MAX_SCALE) size else (size.value * MAX_SCALE / scale).sp
    }

    fun panelLabel() = TextStyle(color = crate { it.textSecondary }, fontSize = capped(11.sp), fontWeight = FontWeight.Medium,
                                 fontFamily = FontFamily.Monospace)

    fun text(size: TextUnit, color: ColorProvider, weight: FontWeight = FontWeight.Normal) =
        TextStyle(color = color, fontSize = capped(size), fontWeight = weight)
}

/** Retrying can't fix these; the widget opens Crate instead. */
internal fun needsApp(f: com.overmap.crate.core.SendFailure?): Boolean =
    f == com.overmap.crate.core.SendFailure.loginExpired || f == com.overmap.crate.core.SendFailure.noData ||
        f == com.overmap.crate.core.SendFailure.unknownPreset

// MARK: Glyph bitmaps

/**
 * Material icons (the SF Symbol mapping of [crateIcon]) rendered as white alpha masks, tinted per appearance by
 * Glance's [ColorFilter.tint]. RemoteViews can't draw vectors from code, so each symbol becomes a small bitmap.
 */
internal object Glyphs {
    private val cache = LruCache<String, Bitmap>(48)

    fun provider(context: Context, symbol: String, size: Dp): ImageProvider =
        ImageProvider(bitmap(symbol, (size.value * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)))

    fun bitmap(symbol: String, px: Int): Bitmap {
        val key = "$symbol@$px"
        cache.get(key)?.let { return it }
        val vector = crateIcon(symbol)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(px / vector.viewportWidth, px / vector.viewportHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
        draw(vector.root, canvas, paint)
        cache.put(key, bmp)
        return bmp
    }

    private fun draw(group: VectorGroup, canvas: Canvas, paint: Paint) {
        canvas.save()
        val m = Matrix().apply {
            postTranslate(-group.pivotX, -group.pivotY)
            postRotate(group.rotation)
            postScale(group.scaleX, group.scaleY)
            postTranslate(group.pivotX + group.translationX, group.pivotY + group.translationY)
        }
        canvas.concat(m)
        for (node in group) {
            when (node) {
                is VectorGroup -> draw(node, canvas, paint)
                is VectorPath -> if (node.fill != null) {
                    val path = node.pathData.toPath().asAndroidPath()
                    path.fillType = if (node.pathFillType == androidx.compose.ui.graphics.PathFillType.EvenOdd)
                        android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
                    paint.alpha = (node.fillAlpha * 255).toInt()
                    canvas.drawPath(path, paint)
                }
            }
        }
        canvas.restore()
    }
}

// MARK: Pieces

/** The preset's identity tile: preset colour, symbol in black (white on ink in light). */
@Composable
internal fun WidgetPresetTile(preset: SharedPreset, size: Dp, alpha: Float = 1f) {
    val color = PresetColor.of(preset.color)
    Glyph(preset.icon, size, WidgetColors.tile(color, alpha), WidgetColors.onTile(color, alpha))
}

/** ✓ on successFill / ✕ on errorFill, the tile's size. */
@Composable
internal fun StatusGlyph(symbol: String, fg: ColorProvider, bg: ColorProvider, size: Dp = 28.dp) =
    Glyph(symbol, size, bg, fg, scale = 0.5f)

@Composable
private fun Glyph(symbol: String, size: Dp, bg: ColorProvider, fg: ColorProvider, scale: Float = 0.55f) {
    val context = LocalContext.current
    Box(
        GlanceModifier.size(size).background(bg).cornerRadius(size * (Radius.chip.value / 32f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(Glyphs.provider(context, symbol, size * scale), contentDescription = null,
              modifier = GlanceModifier.size(size * scale), colorFilter = ColorFilter.tint(fg))
    }
}

/** A plain tinted symbol (the "+" of an empty cell, ✕ of the error strip, ▶ of Listen on Spotify). */
@Composable
internal fun Symbol(symbol: String, size: Dp, tint: ColorProvider) {
    val context = LocalContext.current
    Image(Glyphs.provider(context, symbol, size), contentDescription = null, modifier = GlanceModifier.size(size),
          colorFilter = ColorFilter.tint(tint))
}

/** The small widget's capsule: "Send to Now", "Sending…", "▶ Listen on Spotify", "Open Crate" / "Try again". */
@Composable
internal fun WidgetButtonLabel(text: String, symbol: String?, busy: Boolean, filled: Boolean) {
    val fg = if (filled) crate { it.onAccent } else crate { it.textPrimary }
    val bg = if (filled) crate(if (busy) 0.6f else 1f) { it.accent } else crate { it.fillControl }
    Row(
        GlanceModifier.fillMaxWidth().height(36.dp).background(bg).cornerRadius(18.dp).padding(horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            CircularProgressIndicator(GlanceModifier.size(14.dp), color = fg)
            Spacer(GlanceModifier.width(Space.xs))
        }
        if (symbol != null) {
            Symbol(symbol, 14.dp, fg)
            Spacer(GlanceModifier.width(Space.xs))
        }
        // Glance can't shrink text to fit (SwiftUI minimumScaleFactor 0.7): long labels get the smaller size.
        Text(text, style = WidgetType.text(if (text.length > 14) 13.sp else 15.sp, fg, FontWeight.Medium), maxLines = 1)
    }
}

/** No presets: "CRATE / Save a preset in Crate to send it from here", opens the app. */
@Composable
internal fun EmptyWidget(text: String, modifier: GlanceModifier) {
    Column(modifier.fillMaxSize().padding(Space.l)) {
        Text("CRATE", style = WidgetType.panelLabel())
        Spacer(GlanceModifier.height(Space.s))
        Text(text, style = WidgetType.text(15.sp, crate { it.textPrimary }))
    }
}
