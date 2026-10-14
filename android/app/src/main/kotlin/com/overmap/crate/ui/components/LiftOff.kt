package com.overmap.crate.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.StyleShade
import com.overmap.crate.ui.theme.familyColor
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "Lift-off": the small delight when tracks land in Spotify (twin of iOS `LiftOffEffect`). A luminous wave in the
 * family colours blooms out of the Send button (the NameDrop glow), and covers of the sent tracks peel off the
 * button and float up in staggered arcs, tilting and fading as they fly to Spotify. Purely decorative, 1.5 s, and
 * skipped with reduced motion.
 *
 * Drawn in a non-focusable popup anchored to the button, so it isn't clipped by the button or its parents and
 * flies over the screen's content. Each new [trigger] plays it once.
 */
@Composable
internal fun LiftOff(trigger: Int, covers: List<String>, buttonSize: IntSize, buttonX: Int, buttonY: Int) {
    if (trigger == 0 || buttonSize == IntSize.Zero) return
    val density = LocalDensity.current
    // The layer spans the screen's width (Android trims popups wider than the screen) and reaches from well above
    // the button to below it.
    val screenWidth = LocalWindowInfo.current.containerSize.width
    val side = buttonX.coerceAtLeast(0)
    val above = min(with(density) { 420.dp.roundToPx() }, buttonY.coerceAtLeast(0))
    val below = with(density) { 140.dp.roundToPx() }
    val width = screenWidth
    val height = buttonSize.height + above + below
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(DURATION_MS, easing = LinearEasing)) }
    val t = progress.value
    if (t >= 1f) return

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(-side, -above),
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Box(Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })) {
            val button = RectF(side.toFloat(), above.toFloat(), (side + buttonSize.width).toFloat(), (above + buttonSize.height).toFloat())
            Canvas(Modifier.fillMaxSize()) { bloom(t, button, density.density) }
            val count = min(5, covers.size)
            covers.take(5).forEachIndexed { i, url -> Cover(url, i, count, t, button, density.density) }
        }
    }
}

private const val DURATION_MS = 1500

private val hues = listOf(Family.HOUSE, Family.TECHNO, Family.POP_CANTAUTORI, Family.DISCO, Family.DOWNTEMPO, Family.HOUSE)
    .map { familyColor(it, StyleShade.DEEP).toArgb() }.toIntArray()

/** A soft colour glow and two bright rings growing evenly out of the button's outline, plus a sheen on its face. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.bloom(t: Float, button: RectF, d: Float) {
    val cx = button.centerX(); val cy = button.centerY()
    val radius = Radius.button.value * d
    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        fun sweep(angle: Float) = SweepGradient(cx, cy, hues, null).also {
            it.setLocalMatrix(android.graphics.Matrix().apply { setRotate(angle, cx, cy) })
        }
        // Glow
        val glow = min(1f, t / 0.7f)
        val glowEase = 1 - (1 - glow).pow(2)
        if (glow > 0f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = sweep(glowEase * 160f)
                alpha = (sin(glow * PI) * 0.75 * 255).roundToInt()
                maskFilter = BlurMaskFilter((26 + glowEase * 24) * d, BlurMaskFilter.Blur.NORMAL)
            }
            val gx = glowEase * 30 * d; val gy = glowEase * 75 * d
            c.drawRoundRect(RectF(button.left - gx, button.top - gy, button.right + gx, button.bottom + gy),
                radius + glowEase * 60 * d, radius + glowEase * 60 * d, paint)
        }
        // Rings
        for (ring in 0..1) {
            val local = max(0f, min(1f, (t - ring * 0.14f) / 0.7f))
            if (local == 0f) continue
            val eased = 1 - (1 - local).pow(3)
            val grow = eased * (if (ring == 0) 120f else 80f) * d
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (5 * (1 - eased) + 1) * d
                shader = sweep(eased * 240f + ring * 90f)
                alpha = ((1 - eased) * (if (ring == 0) 1f else 0.7f) * 255).roundToInt()
                maskFilter = BlurMaskFilter((1.5f + eased * 5) * d, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawRoundRect(RectF(button.left - grow, button.top - grow, button.right + grow, button.bottom + grow),
                radius + grow * 0.5f, radius + grow * 0.5f, paint)
        }
        // Sheen across the button face
        if (t < 0.7f) {
            val x = button.left + (-0.6f + t * 2.4f) * button.width()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(x, 0f, x + 0.35f * button.width(), 0f,
                    intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
                alpha = (if (t < 0.55f) 1f else max(0f, 1 - (t - 0.55f) / 0.15f)).times(255).roundToInt()
            }
            c.save(); c.clipRect(button)
            c.drawRoundRect(button, radius, radius, paint)
            c.restore()
        }
    }
}

/** Cover `index` of `count`: starts at the button's top edge, rises along a gentle arc, tilts, shrinks, fades. */
@Composable
private fun Cover(url: String, index: Int, count: Int, t: Float, button: RectF, d: Float) {
    val delay = index * 0.07f
    val local = max(0f, min(1f, (t - 0.1f - delay) / 0.75f))
    if (local == 0f) return
    val rise = 1 - (1 - local).pow(2.2f)
    val spread = index - (count - 1) / 2f
    val sizePx = 52 * d
    val x = button.centerX() + (spread * 38 + sin(rise * PI).toFloat() * spread * 30) * d - sizePx / 2
    val y = button.top - rise * (220 + (index % 2) * 60) * d - sizePx / 2
    val scale = 0.55f + sin(min(1f, rise * 1.5f) * PI / 2).toFloat() * 0.5f - rise * 0.4f
    val alpha = min(1f, local * 6) * (1 - rise.pow(3))
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .graphicsLayer {
                scaleX = scale; scaleY = scale; this.alpha = alpha
                rotationZ = spread * 10 * rise + (if (index % 2 == 0) -5 else 5) * rise
            }
            .shadow(10.dp, androidx.compose.foundation.shape.RoundedCornerShape(Radius.cover)),
    ) {
        CoverImage(url = url, size = 52.dp, radius = Radius.cover)
    }
}
