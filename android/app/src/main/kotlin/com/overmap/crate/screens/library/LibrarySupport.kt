package com.overmap.crate.screens.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.AppConfig
import com.overmap.crate.core.TaxonomyFile
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import java.text.Normalizer
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Shared helpers for the Daily, Library, Presets and Onboarding screens (port of iOS LibrarySupport.swift).

object LibraryText {
    /** "1,015" */
    fun number(n: Int): String = NumberFormat.getIntegerInstance().format(n)

    /** Family display name ("Latin & Brazilian"), from the taxonomy or the design tokens. */
    fun familyName(raw: String, taxonomy: TaxonomyFile): String {
        taxonomy.familyNames[raw]?.let { return it }
        val f = Family.of(raw)
        if (f != Family.NONE) return f.displayName
        return raw.replaceFirstChar { it.uppercase() }
    }

    fun family(raw: String?): Family = Family.of(raw)

    fun parseDate(iso: String?): Instant? {
        if (iso.isNullOrEmpty()) return null
        return runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(iso) }.getOrNull()
    }

    /** "today 08:42", "yesterday 03:12", "Sat 03:12", "12 Oct". */
    fun relativeDay(date: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = date.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val time = d.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val day = d.toLocalDate()
        return when {
            day == today -> "today $time"
            day == today.minusDays(1) -> "yesterday $time"
            ChronoUnit.DAYS.between(date, now) < 7 -> "${d.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))} $time"
            else -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
    }

    /** Whole days between `date` and now. */
    fun daysAgo(date: Instant, now: Instant = Instant.now()): Long = ChronoUnit.DAYS.between(date, now)

    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    /** URL-safe ID from a name ("Italian summer" → "italian-summer"), unique among `existing`. */
    fun slug(name: String, existing: Set<String>): String {
        val folded = fold(name)
        val out = StringBuilder()
        for (ch in folded) {
            if (ch.code < 128 && ch.isLetterOrDigit()) out.append(ch)
            else if (out.isNotEmpty() && out.last() != '-') out.append('-')
        }
        while (out.endsWith("-")) out.setLength(out.length - 1)
        val base = out.toString().ifEmpty { "preset" }
        var candidate = base
        var n = 2
        while (candidate in existing) { candidate = "$base-$n"; n++ }
        return candidate
    }

    /** Runbook link for a health problem (`docs/runbook.md#spotify-token`). */
    fun runbookURL(anchor: String?): String? {
        if (anchor.isNullOrEmpty()) return null
        val path = if (anchor.startsWith("docs/")) anchor else "docs/runbook.md#$anchor"
        return "https://github.com/${AppConfig.REPO_OWNER}/${AppConfig.REPO_NAME}/blob/main/$path"
    }

    /** "Spotify token" from "docs/runbook.md#spotify-token". */
    fun runbookTitle(anchor: String): String {
        val tail = anchor.split("#").last()
        return tail.replace("-", " ").replaceFirstChar { it.uppercase() }
    }

    /** "Tue Oct 13" */
    fun dayLabel(day: LocalDate): String =
        day.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault()))
}

/** Opens Spotify (app if installed, else the web player), or any URL. */
object SpotifyOpen {
    fun playlist(id: String) = "spotify:playlist:$id"
    fun playlistWeb(id: String) = "https://open.spotify.com/playlist/$id"
    const val APP = "spotify:"

    fun open(context: Context, url: String, fallback: String? = null) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            if (fallback != null) runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun openPlaylist(context: Context, id: String?) {
        if (id == null) open(context, APP) else open(context, playlist(id), playlistWeb(id))
    }
}

/** The small rounded chip look of the Daily filter menus (32 dp, 44 at accessibility sizes). */
@Composable
fun LibraryChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val c = CrateTheme.colors
    val shape = RoundedCornerShape(Radius.chip)
    val ax = CrateTheme.isAccessibilitySize
    Box(
        modifier.heightIn(min = Size.minTarget).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) c.onAccent else c.textPrimary,
            LocalTextStyle provides CrateType.chipLabel,
        ) {
            Row(
                Modifier
                    .heightIn(min = if (ax) Size.chipHeightAX else Size.chipHeight)
                    .background(if (selected) c.accent else c.fillControl, shape)
                    .padding(horizontal = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Accent (or control-fill) button used inside cards ("Open Spotify", "Done", "Copy"). */
@Composable
fun CardButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = true,
    fullWidth: Boolean = false,
    enabled: Boolean = true,
) {
    val c = CrateTheme.colors
    Box(modifier.heightIn(min = Size.minTarget), contentAlignment = Alignment.Center) {
        Box(
            (if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = 36.dp)
                .background(if (prominent) c.accent else c.fillControl, RoundedCornerShape(Radius.control))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = Space.m, vertical = Space.xs),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                title, style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold),
                color = if (prominent) c.onAccent else c.textPrimary,
            )
        }
    }
}

/** Full-width 54 dp button for onboarding and secondary screen actions (accent fill, or control fill). */
@Composable
fun LargeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = true,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = CrateTheme.colors
    val shape = RoundedCornerShape(Radius.button)
    CompositionLocalProvider(
        LocalContentColor provides if (prominent) c.onAccent else c.textPrimary,
        LocalTextStyle provides CrateType.body.copy(fontWeight = FontWeight.SemiBold),
    ) {
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = Size.buttonHeight)
                .background((if (prominent) c.accent else c.fillControl).copy(alpha = if (enabled) 1f else 0.4f), shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = Space.l),
            horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** An outlined full-width button ("Open ntfy ↗"). */
@Composable
fun OutlineButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val c = CrateTheme.colors
    val shape = RoundedCornerShape(Radius.control)
    CompositionLocalProvider(
        LocalContentColor provides c.textPrimary.copy(alpha = if (enabled) 1f else 0.4f),
        LocalTextStyle provides CrateType.secondary.copy(fontWeight = FontWeight.SemiBold),
    ) {
        Row(
            modifier.fillMaxWidth().heightIn(min = Size.minTarget)
                .border(1.5.dp, c.textPrimary.copy(alpha = if (enabled) 1f else 0.4f), shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Opens a URL in the browser. */
fun openURL(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
