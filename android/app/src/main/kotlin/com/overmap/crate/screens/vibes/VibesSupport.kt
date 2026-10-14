package com.overmap.crate.screens.vibes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overmap.crate.app.LibraryIndex
import com.overmap.crate.core.Track
import com.overmap.crate.core.Vibe
import com.overmap.crate.core.VibeIndex
import com.overmap.crate.ui.components.PanelLabel
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.VibeSymbols
import com.overmap.crate.ui.theme.crateIcon
import com.overmap.crate.ui.theme.hasCrateIcon
import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale

// Shared helpers for the Vibes screens (editor, manager). Port of iOS VibesSupport.swift. Owner: Vibes agent.

object VibeText {
    /** Icon (SF Symbol name) for a vibe: the user's pick, else the default for its ID, else `tag`. Unknown names fall back to `tag`. */
    fun symbol(id: String?, vibe: Vibe?): String {
        val name = vibe?.icon ?: id?.let { VibeSymbols.defaults[it] } ?: "tag"
        return if (hasCrateIcon(name)) name else "tag"
    }

    /** URL-safe ID from a name: "Sunday morning" → "sunday-morning", unique among [existing]. */
    fun slug(name: String, existing: Collection<String>): String {
        val folded = fold(name)
        val out = StringBuilder()
        var dash = false
        for (ch in folded) {
            if (ch.isLetterOrDigit() && ch.code < 128) {
                out.append(ch); dash = false
            } else if (out.isNotEmpty() && !dash) {
                out.append('-'); dash = true
            }
        }
        while (out.endsWith("-")) out.setLength(out.length - 1)
        var base = out.toString().ifEmpty { "vibe" }
        val taken = existing.toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        base = "$base-$n"
        return base
    }

    /** "BI" for "Bon Iver", "BO" for "Bonobo". */
    fun initials(name: String): String {
        val words = name.split(' ', '-').filter { it.firstOrNull()?.isLetter() == true }
        if (words.size >= 2) return (words[0].take(1) + words[1].take(1)).uppercase()
        return (words.firstOrNull() ?: name).take(2).uppercase()
    }

    /** Manager subtitle: "5 seed tracks", "3 tags · 4 never", "No seeds yet". */
    fun summary(vibe: Vibe): String {
        val parts = mutableListOf<String>()
        if (vibe.seeds.isNotEmpty()) parts += if (vibe.seeds.size == 1) "1 seed track" else "${vibe.seeds.size} seed tracks"
        if (vibe.artists.isNotEmpty()) parts += if (vibe.artists.size == 1) "1 artist" else "${vibe.artists.size} artists"
        if (vibe.seedTags.isNotEmpty()) parts += if (vibe.seedTags.size == 1) "1 tag" else "${vibe.seedTags.size} tags"
        if (parts.isEmpty()) return "No seeds yet"
        if (vibe.exclude.isNotEmpty()) parts += "${vibe.exclude.size} never"
        return parts.joinToString(" · ")
    }

    fun hasSeeds(vibe: Vibe): Boolean = vibe.seeds.isNotEmpty() || vibe.artists.isNotEmpty() || vibe.seedTags.isNotEmpty()

    /** Case- and diacritic-insensitive form for matching. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

    /** TalkBack name of a picker symbol: "moon.fill" → "moon". */
    fun symbolLabel(name: String): String = name.replace(".fill", "").replace(".", " ")
}

/** The three library shares of the editor's segmented control. */
enum class VibeShare(val value: Double, val title: String) {
    TIGHT(VibeSymbols.SHARE_TIGHT, "Tight"),
    NORMAL(VibeSymbols.SHARE_NORMAL, "Normal"),
    WIDE(VibeSymbols.SHARE_WIDE, "Wide");

    val label: String get() = "$title ${Math.round(value * 100)}%"

    companion object {
        fun of(share: Double): VibeShare? = entries.firstOrNull { kotlin.math.abs(it.value - share) < 0.001 }
    }
}

/** Track lookups shared by the vibe screens. */
class VibeLookup(val index: LibraryIndex) {
    fun title(id: String): String = index.tracks[id]?.title ?: "Unknown track"
    fun artist(id: String): String = index.tracks[id]?.artists?.joinToString(", ") ?: ""
    fun cover(id: String): String? = index.tracks[id]?.cover
    fun family(id: String): Family? = index.effective[id]?.family?.let { Family.of(it) }?.takeIf { it != Family.NONE }
}

/** Result of ranking a draft vibe off the main thread. */
data class VibePreview(
    /** Members, closest first. */
    val members: List<String> = emptyList(),
    /** Tracks that `seed_tags` pull in (before the "must sound like it" check). */
    val tagSeedCount: Int = 0,
) {
    val isEmpty: Boolean get() = members.isEmpty()

    companion object {
        fun compute(vibe: Vibe, index: VibeIndex): VibePreview {
            val memberSet = index.members(vibe)
            val members = index.ranked(vibe).map { it.id }.filter { it in memberSet }.toMutableList()
            if (members.size < memberSet.size) {
                val seen = members.toSet()
                members += (memberSet - seen).sorted()
            }
            return VibePreview(members, index.tagSeeds(vibe).size)
        }
    }
}

/** Searchable artist list (name → track count), built once per library. */
class ArtistDirectory(tracks: Map<String, Track>) {
    data class Entry(val name: String, val count: Int, val folded: String)

    val entries: List<Entry>

    init {
        val counts = HashMap<String, Pair<String, Int>>()
        for (t in tracks.values) {
            for (a in t.artists ?: emptyList()) {
                val key = VibeText.fold(a)
                val cur = counts[key]
                counts[key] = (cur?.first ?: a) to ((cur?.second ?: 0) + 1)
            }
        }
        entries = counts.map { (k, v) -> Entry(v.first, v.second, k) }
            .sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.name })
    }

    fun search(q: String, limit: Int): List<Entry> {
        val f = VibeText.fold(q)
        val prefix = entries.filter { it.folded.startsWith(f) }
        val contains = entries.filter { !it.folded.startsWith(f) && it.folded.contains(f) }
        return (prefix + contains).take(limit)
    }

    fun count(name: String): Int = VibeText.fold(name).let { f -> entries.firstOrNull { it.folded == f }?.count ?: 0 }
}

/** The vibe's symbol on a rounded tile (36 dp in the editor and manager). Decorative. */
@Composable
fun VibeSymbolTile(symbol: String, modifier: Modifier = Modifier, size: Dp = 36.dp, selected: Boolean = false, dimmed: Boolean = false) {
    val colors = CrateTheme.colors
    Box(
        modifier
            .size(size)
            .background(if (selected) colors.accent else colors.fillControl, RoundedCornerShape(Radius.control)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            crateIcon(symbol), null, Modifier.size(size * 0.5f),
            tint = if (selected) colors.onAccent else if (dimmed) colors.textSecondary else colors.textPrimary,
        )
    }
}

/** 32 dp initials circle for artist seeds. Decorative. */
@Composable
fun InitialsCircle(name: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val colors = CrateTheme.colors
    Box(modifier.size(size).background(colors.fillControl, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(
            VibeText.initials(name),
            style = CrateType.footnote.copy(fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.SemiBold),
            color = colors.textSecondary, maxLines = 1,
        )
    }
}

/** The "−" remove control of seed and Never rows (44 dp target). */
@Composable
fun VibeMinusButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .sizeIn(minWidth = Size.minTarget, minHeight = Size.minTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Remove, null, Modifier.size(20.dp), tint = CrateTheme.colors.textSecondary)
    }
}

/** iOS-style segmented control: a `fillControl` track with the selected segment on `fillControlActive`. */
@Composable
fun VibeSegmented(options: List<String>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    val ax = CrateTheme.isAccessibilitySize
    val inner: @Composable (Int, String, Modifier) -> Unit = { i, title, m ->
        val on = i == selected
        Box(
            m.heightIn(min = Size.minTarget)
                .clip(RoundedCornerShape(Radius.control - 2.dp))
                .background(if (on) colors.fillControlActive else Color.Transparent)
                .clickable { onSelect(i) }
                .semantics { this.selected = on; role = Role.Tab }
                .padding(horizontal = Space.s, vertical = Space.xs),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title, style = CrateType.secondary.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                color = colors.textPrimary, textAlign = TextAlign.Center,
            )
        }
    }
    val track = modifier.fillMaxWidth().background(colors.fillControl, RoundedCornerShape(Radius.control)).padding(2.dp)
    if (ax) {
        Column(track, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, t -> inner(i, t, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(track, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, t -> inner(i, t, Modifier.weight(1f)) }
        }
    }
}

/** Section header in the Crate panel-label style (iOS `vibeSectionHeader`). */
@Composable
fun VibeSectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    PanelLabel(text, modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.s, start = Space.l, end = Space.l), trailing)
}

/** Shape of one row inside an inset-grouped card. */
fun groupShape(first: Boolean, last: Boolean): Shape {
    val r = Radius.card
    return RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
}

/** One row of an inset-grouped card: `bgCard` (or [fill]) with corners at the ends and a hairline between rows. */
@Composable
fun GroupRow(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    fill: Color = CrateTheme.colors.bgCard,
    dividerInset: Dp = Space.l,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.screenMargin).clip(groupShape(first, last)).background(fill)) {
        content()
        if (!last) HorizontalDivider(Modifier.padding(start = dividerInset), thickness = 0.5.dp, color = CrateTheme.colors.separator)
    }
}
