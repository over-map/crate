package com.overmap.crate.screens.daily

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.overmap.crate.app.AppModel
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.core.EffectiveTags
import com.overmap.crate.core.WeeklyFile
import com.overmap.crate.core.WeeklyTrack
import com.overmap.crate.screens.library.LargeButton
import com.overmap.crate.screens.library.LargeTitleHeader
import com.overmap.crate.screens.library.LibraryChip
import com.overmap.crate.screens.library.LibraryText
import com.overmap.crate.screens.library.RowSeparator
import com.overmap.crate.screens.library.SpotifyOpen
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.ChipFlow
import com.overmap.crate.ui.components.CoverImage
import com.overmap.crate.ui.components.FamilyMiniChip
import com.overmap.crate.ui.components.LikeButton
import com.overmap.crate.ui.components.PanelLabel
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.familyColor
import com.overmap.crate.ui.theme.onData
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Daily tab (design 6q–6r, "Crate Daily"): today's taste families, filters, "Your taste" and "Discovery" sections,
 * why-this-track notes and likes. Port of iOS `WeeklyView`.
 */
@Composable
fun DailyScreen() {
    val model = rememberModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val weekly = model.snapshot.weekly
    val taxonomy = model.snapshot.taxonomy

    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var liked by remember { mutableStateOf(setOf<String>()) }
    var liking by remember { mutableStateOf(setOf<String>()) }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    var vibe by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var likedOnly by rememberSaveable { mutableStateOf(false) }
    var likeError by remember { mutableStateOf<String?>(null) }
    val debugToday = remember { DebugLaunch.screen == "daily-today" }

    // Debug hook: `--es screen daily-why` opens the first taste row's reason.
    LaunchedEffect(Unit) {
        if (DebugLaunch.screen == "daily-why" || DebugLaunch.screen == "weekly-why") {
            expanded = weekly.tracks.firstOrNull { it.kind != WeeklyTrack.Kind.discovery }?.id
        }
    }

    // Liked state from Spotify.
    LaunchedEffect((weekly.date ?: "") + (weekly.week ?: "")) {
        if (model.isDemo || !model.spotify.isConnected || weekly.tracks.isEmpty()) return@LaunchedEffect
        val uris = weekly.tracks.map { it.uri ?: "spotify:track:${it.id}" }
        val flags = runCatching { model.spotify.contains(uris) }.getOrNull() ?: return@LaunchedEffect
        liked = weekly.tracks.zip(flags).filter { it.second }.map { it.first.id }.toSet()
    }

    val playlistID = weekly.playlistID ?: model.snapshot.state.weeklyPlaylistID
    val openPlaylist = { playlistID?.let { SpotifyOpen.openPlaylist(context, it) }; Unit }

    fun like(t: WeeklyTrack) {
        if (model.isDemo) {
            liked = if (t.id in liked) liked - t.id else liked + t.id
            return
        }
        // The heart toggles: like saves to Liked Songs, a second tap removes it again.
        if (t.id in liking) return
        val wasLiked = t.id in liked
        val uri = t.uri ?: "spotify:track:${t.id}"
        liked = if (wasLiked) liked - t.id else liked + t.id
        liking = liking + t.id
        scope.launch {
            try {
                if (wasLiked) model.spotify.unlike(uri) else model.spotify.like(uri)
            } catch (_: Exception) {
                liked = if (wasLiked) liked + t.id else liked - t.id
                likeError = if (wasLiked) "Spotify didn't remove the like. Try again in a moment."
                else "Spotify didn't accept the like. Try again in a moment."
            }
            liking = liking - t.id
        }
    }

    Column(Modifier.fillMaxSize().background(CrateTheme.colors.bgBase)) {
        LargeTitleHeader("Daily") {
            if (playlistID != null) {
                IconButton(onClick = openPlaylist) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open Crate Daily in Spotify", tint = CrateTheme.colors.textPrimary)
                }
            }
        }
        if (weekly.tracks.isEmpty()) {
            EmptyDaily()
            return@Column
        }

        val data = remember(model.snapshot, model.index) { DailyData(model, weekly) }
        val filter = DailyFilter(family, vibe, tag, likedOnly, liked)
        val taste = data.filtered(weekly.tracks.filter { it.kind != WeeklyTrack.Kind.discovery }, filter)
        val discovery = data.filtered(weekly.tracks.filter { it.kind == WeeklyTrack.Kind.discovery }, filter)
        val clearFilters = { family = null; vibe = null; tag = null; likedOnly = false }
        val toggleWhy = { id: String -> expanded = if (expanded == id) null else id }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.screenMargin).padding(top = Space.s, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
                Text(
                    headerLabel(weekly, debugToday = debugToday).uppercase(), style = CrateType.panelLabel,
                    color = CrateTheme.colors.textSecondary, modifier = Modifier.semantics { heading() },
                )
                ChipFlow(spacing = Space.chipGap, lineSpacing = Space.chipGap) {
                    data.tasteFamilies.forEach { f ->
                        DailyFamilyChip(LibraryText.family(f), LibraryText.familyName(f, taxonomy))
                    }
                }
                Text(
                    "Genres rotate every day. Like what you love and the mix learns.",
                    style = CrateType.footnote, color = CrateTheme.colors.textSecondary,
                )
            }

            // Filters
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.chipGap),
            ) {
                FilterMenu(
                    title = "Genre", value = family?.let { LibraryText.familyName(it, taxonomy) },
                    options = data.allFamilies.map { it to LibraryText.familyName(it, taxonomy) },
                    anyTitle = "Any genre", selection = family, onSelect = { family = it },
                )
                FilterMenu(
                    title = "Vibe", value = vibe?.let { data.vibeName(it) },
                    options = data.vibeOptions.map { it to data.vibeName(it) },
                    anyTitle = "Any vibe", selection = vibe, onSelect = { vibe = it },
                )
                FilterMenu(
                    title = "Tag", value = tag?.let { "⌗ $it" },
                    options = data.tagOptions.map { it to it },
                    anyTitle = "Any tag", selection = tag, onSelect = { tag = it },
                )
                LibraryChip(
                    selected = likedOnly, onClick = { likedOnly = !likedOnly },
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = "Liked only"
                        role = Role.Button
                        selected = likedOnly
                        onClick { likedOnly = !likedOnly; true }
                    },
                ) {
                    if (likedOnly) Icon(Icons.Filled.Favorite, null, Modifier.size(14.dp))
                    Text("Liked")
                }
            }

            if (taste.isEmpty() && discovery.isEmpty()) {
                Column(Modifier.padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("No tracks match these filters.", style = CrateType.body, color = CrateTheme.colors.textSecondary)
                    Box(
                        Modifier.heightIn(min = Size.minTarget).clickable(role = Role.Button, onClick = clearFilters),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("Clear filters", style = CrateType.body.copy(fontWeight = FontWeight.SemiBold), color = CrateTheme.colors.accent)
                    }
                }
            }
            if (taste.isNotEmpty()) {
                DailySection(
                    "Your taste · ${taste.size}", data.perGenreLabel.takeIf { filter.isEmpty }, null, taste, data,
                    expanded, liked, onWhy = toggleWhy, onLike = ::like,
                )
            }
            if (discovery.isNotEmpty()) {
                DailySection(
                    "Discovery · ${discovery.size}", "Genres you have little of",
                    "Ranked by how close they are to your overall sound.", discovery, data, expanded, liked,
                    onWhy = toggleWhy, onLike = ::like,
                )
            }
            if (playlistID != null) {
                LargeButton(onClick = openPlaylist, prominent = false, modifier = Modifier.padding(top = Space.s)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(20.dp))
                    Text("Open Crate Daily in Spotify")
                }
            }
        }
    }

    likeError?.let { message ->
        AlertDialog(
            onDismissRequest = { likeError = null },
            confirmButton = { TextButton({ likeError = null }) { Text("OK") } },
            title = { Text("Couldn't change the like") },
            text = { Text(message) },
            containerColor = CrateTheme.colors.bgSheet,
        )
    }
}

@Composable
private fun EmptyDaily() {
    Column(
        Modifier.fillMaxSize().padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.WbTwilight, null, Modifier.size(48.dp), tint = CrateTheme.colors.textSecondary)
        Text("No Crate Daily yet", style = CrateType.sheetTitle, color = CrateTheme.colors.textPrimary)
        Text(
            "It arrives every morning before 06:00.", style = CrateType.secondary,
            color = CrateTheme.colors.textSecondary, textAlign = TextAlign.Center,
        )
    }
}

private data class DailyFilter(
    val family: String?, val vibe: String?, val tag: String?, val likedOnly: Boolean, val liked: Set<String>,
) {
    val isEmpty: Boolean get() = family == null && vibe == null && tag == null && !likedOnly
}

/** Derived lookups over today's mix (families, vibe and tag options, reasons). */
private class DailyData(private val model: AppModel, private val weekly: WeeklyFile) {
    private val taxonomy = model.snapshot.taxonomy

    /** The mix's taste families in mix order (5 per the design). */
    val tasteFamilies: List<String> = weekly.tracks.filter { it.kind != WeeklyTrack.Kind.discovery }
        .mapNotNull { it.family }.distinct()

    val allFamilies: List<String> = weekly.tracks.mapNotNull { it.family }.distinct()

    val perGenreLabel: String? = run {
        val taste = weekly.tracks.count { it.kind != WeeklyTrack.Kind.discovery }
        val fams = tasteFamilies.size
        if (fams > 0 && taste % fams == 0) "${taste / fams} per genre" else null
    }

    fun vibeName(id: String): String = model.snapshot.vibes.vibes[id]?.name ?: id.replaceFirstChar { it.uppercase() }

    /** New tracks are not in the library, so vibes use the audio model's matching mood (same IDs). */
    fun hasVibe(t: WeeklyTrack, id: String): Boolean = (t.auto?.moods?.get(id) ?: 0.0) >= EffectiveTags.moodThreshold

    val vibeOptions: List<String> = model.snapshot.vibes.ordered.map { it.id }
        .filter { id -> weekly.tracks.any { hasVibe(it, id) } }

    /** Community tags of the same artists in the library (Last.fm tags describe the artist anyway). */
    private val artistTags: Map<String, Set<String>> = run {
        val wanted = weekly.tracks.flatMap { it.artists.orEmpty() }.toSet()
        val out = HashMap<String, MutableSet<String>>()
        for ((id, track) in model.index.tracks) {
            for (a in track.artists.orEmpty()) if (a in wanted) {
                out.getOrPut(a) { mutableSetOf() }.addAll(model.index.effective[id]?.communityTags.orEmpty())
            }
        }
        out
    }

    private fun tags(t: WeeklyTrack): Set<String> = t.artists.orEmpty().flatMap { artistTags[it].orEmpty() }.toSet()

    val tagOptions: List<String> = run {
        val counts = HashMap<String, Int>()
        for (t in weekly.tracks) for (tag in tags(t)) counts.merge(tag, 1, Int::plus)
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(12).map { it.key }
    }

    fun filtered(tracks: List<WeeklyTrack>, f: DailyFilter): List<WeeklyTrack> = tracks.filter { t ->
        if (f.family != null && t.family != f.family) return@filter false
        if (f.vibe != null && !hasVibe(t, f.vibe)) return@filter false
        if (f.tag != null && f.tag !in tags(t)) return@filter false
        if (f.likedOnly && t.id !in f.liked) return@filter false
        true
    }

    fun familyName(t: WeeklyTrack): String? = t.family?.let { LibraryText.familyName(it, taxonomy) }

    /**
     * "Taste · House: sounds closest to “Title” by Artist, which you liked." when the pick has a vector, else the
     * pipeline's reason, else one built from what is known.
     */
    fun reason(t: WeeklyTrack): String {
        val name = familyName(t) ?: "No genre"
        closestLiked(t)?.let { closest ->
            val kind = if (t.kind == WeeklyTrack.Kind.discovery) "Discovery" else "Taste"
            return "$kind · $name: sounds closest to $closest, which you liked."
        }
        t.reason?.takeIf { it.isNotEmpty() }?.let { return it }
        val have = t.family?.let { f -> model.index.families.firstOrNull { it.family == f }?.count } ?: 0
        if (t.kind == WeeklyTrack.Kind.discovery) {
            val owned = if (have == 0) "you have no $name tracks yet"
            else "you have $have $name track${if (have == 1) "" else "s"}"
            return "Discovery · $name: $owned. It sits close to your overall sound."
        }
        return "Taste · $name: in today's rotation, close to the ${LibraryText.number(have)} $name tracks you liked."
    }

    private val closestCache = HashMap<String, String?>()

    /** The library track that sounds most like a Daily pick: "“Teardrop” by Massive Attack". */
    private fun closestLiked(t: WeeklyTrack): String? {
        if (closestCache.containsKey(t.id)) return closestCache[t.id]
        val result = run {
            val v = t.vector ?: return@run null
            val index = model.snapshot.vectors ?: return@run null
            val id = index.nearest(vector = v, count = 2).firstOrNull { it.id != t.id }?.id ?: return@run null
            val track = model.index.tracks[id] ?: return@run null
            val title = track.title ?: return@run null
            val artist = track.artists?.firstOrNull()?.let { " by $it" } ?: ""
            "“$title”$artist"
        }
        closestCache[t.id] = result
        return result
    }
}

/**
 * "TODAY · TUE OCT 13 · YOUR TASTE TODAY"; "YESTERDAY · MON OCT 12 · YOUR TASTE" or "SAT OCT 10 · YOUR TASTE"
 * for an older mix; "WEEK 42 · OCT 12–18 · YOUR TASTE" for an old weekly file with no usable day.
 */
internal fun headerLabel(file: WeeklyFile, now: LocalDate = LocalDate.now(), debugToday: Boolean = false): String {
    val day = if (debugToday) now else file.mixDay(ZoneId.systemDefault())
    if (day != null) {
        val date = LibraryText.dayLabel(day)
        // A mix stamped later than today (time zones) still counts as today's.
        if (!day.isBefore(now)) return "Today · $date · Your taste today"
        if (day == now.minusDays(1)) return "Yesterday · $date · Your taste"
        return "$date · Your taste"
    }
    isoWeek(file.week)?.let { (week, monday) ->
        val sunday = monday.plusDays(6)
        val md = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        val start = monday.format(md)
        val end = if (monday.month == sunday.month) sunday.dayOfMonth.toString() else sunday.format(md)
        return "Week $week · $start–$end · Your taste"
    }
    return "Your taste"
}

internal fun isoWeek(label: String?): Pair<Int, LocalDate>? {
    val bits = label?.split("-W") ?: return null
    if (bits.size != 2) return null
    val year = bits[0].toIntOrNull() ?: return null
    val week = bits[1].toIntOrNull() ?: return null
    val monday = runCatching {
        LocalDate.of(year, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong()).with(DayOfWeek.MONDAY)
    }.getOrNull() ?: return null
    return week to monday
}

/** The 28 dp family chip of the Daily header. */
@Composable
private fun DailyFamilyChip(family: Family, title: String) {
    Box(
        Modifier
            .clearAndSetSemantics { contentDescription = "Genre $title" }
            .heightIn(min = 28.dp)
            .background(familyColor(family, dark = CrateTheme.colors.isDark), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = CrateType.footnote.copy(fontWeight = FontWeight.SemiBold), color = CrateTheme.colors.onData, maxLines = 1)
    }
}

@Composable
private fun FilterMenu(
    title: String,
    value: String?,
    options: List<Pair<String, String>>,
    anyTitle: String,
    selection: String?,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        LibraryChip(
            selected = value != null, onClick = { open = true },
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = title
                stateDescription = value ?: "Any"
                role = Role.DropdownList
                onClick { open = true; true }
            },
        ) {
            Text(value ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = CrateTheme.colors.bgSheet) {
            DropdownMenuItem(text = { Text(anyTitle) }, onClick = { onSelect(null); open = false })
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(if (title == "Tag") "No tags for today's artists" else "Nothing to filter") },
                    onClick = {}, enabled = false,
                )
            }
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(id); open = false },
                    trailingIcon = if (selection == id) ({ Icon(Icons.Filled.Check, "Selected") }) else null,
                )
            }
        }
    }
}

@Composable
private fun DailySection(
    label: String,
    trailing: String?,
    note: String?,
    tracks: List<WeeklyTrack>,
    data: DailyData,
    expanded: String?,
    liked: Set<String>,
    onWhy: (String) -> Unit,
    onLike: (WeeklyTrack) -> Unit,
) {
    Column {
        PanelLabel(label, trailing = trailing)
        if (note != null) {
            Text(note, style = CrateType.footnote, color = CrateTheme.colors.textSecondary, modifier = Modifier.padding(top = Space.xs))
        }
        Column(Modifier.padding(top = Space.xs)) {
            tracks.forEach { t ->
                DailyRow(
                    track = t, familyName = data.familyName(t), reason = { data.reason(t) },
                    isExpanded = expanded == t.id, isLiked = t.id in liked,
                    onWhy = { onWhy(t.id) }, onLike = { onLike(t) },
                )
                RowSeparator()
            }
        }
    }
}

/** One Daily row: cover, title, family chip + artist, "?" toggle and heart; the reason note expands below. */
@Composable
private fun DailyRow(
    track: WeeklyTrack,
    familyName: String?,
    reason: () -> String,
    isExpanded: Boolean,
    isLiked: Boolean,
    onWhy: () -> Unit,
    onLike: () -> Unit,
) {
    val c = CrateTheme.colors
    val ax = CrateTheme.isAccessibilitySize
    val family = LibraryText.family(track.family)
    val artist = track.artists.orEmpty().joinToString(", ")

    val cover: @Composable () -> Unit = {
        CoverImage(track.cover, size = Size.coverRow, radius = Radius.cover, tint = familyColor(family, dark = c.isDark))
    }
    val info: @Composable (Modifier) -> Unit = { m ->
        Column(m.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                track.title ?: "Unknown track", style = CrateType.body, color = c.textPrimary,
                maxLines = if (ax) 3 else 1, overflow = TextOverflow.Ellipsis,
            )
            if (ax) {
                if (familyName != null) FamilyMiniChip(family, title = familyName)
                Text(artist, style = CrateType.secondary, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    if (familyName != null) FamilyMiniChip(family, title = familyName)
                    Text(
                        artist, style = CrateType.secondary, color = c.textSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().heightIn(min = Size.coverRow + Space.l).padding(vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (ax) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
                cover(); info(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End)) {
                WhyButton(isExpanded, onWhy); LikeButton(isLiked, onLike)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                cover(); info(Modifier.weight(1f)); WhyButton(isExpanded, onWhy); LikeButton(isLiked, onLike)
            }
        }
        val reduce = CrateTheme.reduceMotion
        AnimatedVisibility(
            isExpanded,
            enter = if (reduce) fadeIn() else fadeIn() + expandVertically(),
            exit = if (reduce) fadeOut() else fadeOut() + shrinkVertically(),
        ) {
            Text(
                reason(), style = CrateType.footnote, color = c.textSecondary,
                modifier = Modifier
                    .padding(start = if (ax) 0.dp else Size.coverRow + Space.m)
                    .fillMaxWidth()
                    .background(c.fillControl, RoundedCornerShape(Radius.control))
                    .padding(Space.m),
            )
        }
    }
}

/** The 28 dp "?" circle in a 44 dp target; filled accent when the reason is open. */
@Composable
private fun WhyButton(isExpanded: Boolean, onClick: () -> Unit) {
    val c = CrateTheme.colors
    // Capped like iOS `.dynamicTypeSize(...xxLarge)` so the glyph stays inside the 28 dp circle.
    val scale = LocalDensity.current.fontScale.coerceAtLeast(0.5f)
    Box(
        Modifier
            .size(Size.minTarget)
            .clearAndSetSemantics {
                contentDescription = "Why this track?"
                role = Role.Button
                if (isExpanded) selected = true
                onClick { onClick(); true }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(28.dp)
                .then(if (isExpanded) Modifier.background(c.accent, CircleShape) else Modifier.border(1.5.dp, c.textSecondary, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "?", style = CrateType.footnote.copy(fontWeight = FontWeight.Bold, fontSize = (13f * minOf(scale, 1.35f) / scale).sp),
                color = if (isExpanded) c.onAccent else c.textPrimary,
            )
        }
    }
}
