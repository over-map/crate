package com.overmap.crate.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Crate semantic colours of the current appearance. */
val LocalCrateColors = staticCompositionLocalOf { DarkCrateColors }

/**
 * `true` when the user turned animations off (Settings › Accessibility › Remove animations, or an animator
 * duration scale of 0): the Android equivalent of iOS Reduce Motion. Components then crossfade instead.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Root theme: provides [LocalCrateColors], [LocalReduceMotion], 44 dp touch targets and a Material 3 theme mapped
 * onto the Crate tokens, so stock Material components (Switch, TextField, NavigationBar, ModalBottomSheet,
 * AlertDialog …) match. Dark uses a true black `bgBase`.
 */
@Composable
fun CrateTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkCrateColors else LightCrateColors
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        scale == 0f
    }
    val baseConfig = LocalViewConfiguration.current
    val viewConfig = remember(baseConfig) { CrateViewConfiguration(baseConfig) }

    MaterialTheme(colorScheme = materialColors(colors), typography = CrateTypography, shapes = CrateShapes) {
        CompositionLocalProvider(
            LocalCrateColors provides colors,
            LocalReduceMotion provides reduceMotion,
            LocalViewConfiguration provides viewConfig,
            LocalMinimumInteractiveComponentSize provides Size.minTarget,
            content = content,
        )
    }
}

/** Accessor: `CrateTheme.colors.textPrimary`. */
object CrateTheme {
    val colors: CrateColors
        @Composable @ReadOnlyComposable get() = LocalCrateColors.current

    /** Whether animations should be replaced by a 0.2 s crossfade. */
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current

    /**
     * Android's take on iOS "accessibility sizes" (AX1+): a font scale of 1.5 or more. Chips grow to 44 dp, labels
     * wrap, side-by-side labels stack.
     */
    val isAccessibilitySize: Boolean
        @Composable @ReadOnlyComposable get() = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE

    const val ACCESSIBILITY_FONT_SCALE = 1.5f
}

/** Text and glyphs on data colours (family, Camelot, preset tiles): always black (design-system §2). */
val CrateColors.onData: Color get() = Color.Black

/** `fillControlStrong` in the HTML (#3A3A3C): unlit micro-meter ticks, dashed outlines. */
val CrateColors.fillControlStrong: Color get() = textTertiary.copy(alpha = 0.5f)

/** System-style slider thumb (white in both appearances). */
val CrateColors.thumb: Color get() = Color.White

/** Hit testing grows any pointer-input element smaller than this to 44 × 44 dp (design `Size.minTarget`). */
private class CrateViewConfiguration(private val base: ViewConfiguration) : ViewConfiguration by base {
    override val minimumTouchTargetSize: DpSize get() = DpSize(Size.minTarget, Size.minTarget)
}

private fun materialColors(c: CrateColors): ColorScheme {
    val scheme = if (c.isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.fillControlActive,
        onPrimaryContainer = c.textPrimary,
        inversePrimary = c.onAccent,
        secondary = c.textSecondary,
        onSecondary = c.bgBase,
        secondaryContainer = c.fillControlActive,
        onSecondaryContainer = c.textPrimary,
        tertiary = c.bpm,
        onTertiary = Color.Black,
        tertiaryContainer = c.fillControl,
        onTertiaryContainer = c.textPrimary,
        background = c.bgBase,
        onBackground = c.textPrimary,
        surface = c.bgBase,
        onSurface = c.textPrimary,
        surfaceVariant = c.fillControl,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = Color.Transparent,
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.bgBase,
        error = c.error,
        onError = if (c.isDark) Color.Black else Color.White,
        errorContainer = c.errorFill,
        onErrorContainer = c.textPrimary,
        outline = c.textTertiary,
        outlineVariant = c.separator,
        scrim = Color.Black.copy(alpha = 0.6f),
        surfaceBright = c.fillControlActive,
        surfaceDim = c.bgBase,
        surfaceContainerLowest = c.bgBase,
        surfaceContainerLow = c.bgSheet,
        surfaceContainer = c.bgCard,
        surfaceContainerHigh = c.bgCard,
        surfaceContainerHighest = c.fillControl,
    )
}

private val CrateTypography = Typography().let { t ->
    t.copy(
        displayLarge = CrateType.numeralHero,
        displayMedium = CrateType.screenTitle,
        displaySmall = CrateType.numeralLarge,
        headlineLarge = CrateType.screenTitle,
        headlineMedium = CrateType.sheetTitle.copy(fontSize = 28.sp),
        headlineSmall = CrateType.sheetTitle,
        titleLarge = CrateType.sheetTitle,
        titleMedium = CrateType.headline,
        titleSmall = CrateType.chipLabel,
        bodyLarge = CrateType.body,
        bodyMedium = CrateType.secondary,
        bodySmall = CrateType.footnote,
        labelLarge = CrateType.chipLabel,
        labelMedium = CrateType.footnote.copy(fontWeight = CrateType.tagLine.fontWeight),
        labelSmall = CrateType.panelLabel,
    )
}

private val CrateShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.badge),
    small = RoundedCornerShape(Radius.chip),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.button),
    extraLarge = RoundedCornerShape(16.dp),
)
