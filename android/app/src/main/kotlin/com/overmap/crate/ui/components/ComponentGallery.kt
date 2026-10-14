package com.overmap.crate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.PresetColor
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.Stroke
import com.overmap.crate.ui.theme.StyleShade
import com.overmap.crate.ui.theme.VibeSymbols
import com.overmap.crate.ui.theme.crateIcon
import com.overmap.crate.ui.theme.crateIconNames
import com.overmap.crate.ui.theme.familyColor
import com.overmap.crate.ui.theme.familyOutline
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val gallerySections = listOf(
    "foundation" to "Foundation", "button" to "PrimaryButton", "style" to "StyleChip · Genre",
    "vibe" to "VibeChip", "tag" to "TagChip · suggestions", "confidence" to "ConfidenceLine",
    "bpm" to "≈ BPM", "energy" to "Energy", "slider" to "RangeSlider · BPM",
    "camelot" to "Camelot", "track" to "TrackRow · tag line", "preset" to "Presets · ticks",
    "icons" to "SF Symbol → Material", "material" to "Material (themed)",
)

/**
 * Every CrateUI component in every state, for design review (debug builds). The app shows it instead of the
 * normal root when launched with the gallery intent extra; [initialSection] (foundation, button, style, vibe, tag,
 * confidence, bpm, energy, slider, camelot, track, preset, icons, material) starts scrolled to a section.
 */
@Composable
fun ComponentGallery(modifier: Modifier = Modifier, initialSection: String? = null) {
    val colors = CrateTheme.colors
    val listState = rememberLazyListState()
    LaunchedEffect(initialSection) {
        val index = gallerySections.indexOfFirst { it.first == initialSection }
        if (index >= 0) listState.scrollToItem(index + 1)
    }
    LazyColumn(
        modifier.fillMaxSize().background(colors.bgBase).windowInsetsPadding(WindowInsets.safeDrawing),
        state = listState,
        contentPadding = PaddingValues(horizontal = Space.screenMargin, vertical = Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        item { Text("CrateUI", style = CrateType.screenTitle, color = colors.textPrimary) }
        itemsIndexed(gallerySections, key = { _, s -> s.first }) { _, (id, title) ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(title, style = CrateType.sheetTitle, color = colors.textPrimary)
                when (id) {
                    "foundation" -> FoundationDemo()
                    "button" -> ButtonDemo()
                    "style" -> StyleDemo()
                    "vibe" -> VibeDemo()
                    "tag" -> TagDemo()
                    "confidence" -> ConfidenceDemo()
                    "bpm" -> BPMDemo()
                    "energy" -> EnergyDemo()
                    "slider" -> SliderDemo()
                    "camelot" -> CamelotDemo()
                    "track" -> TrackDemo()
                    "preset" -> PresetDemo()
                    "icons" -> IconsDemo()
                    "material" -> MaterialDemo()
                }
            }
        }
    }
}

/** A captioned group of states, on a card. */
@Composable
private fun Specimen(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().crateCard().padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        PanelLabel(title)
        content()
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace), color = CrateTheme.colors.textTertiary)
}

// region Demos

@Composable
private fun FoundationDemo() {
    Specimen("Panel label · card") {
        PanelLabel("Genre & style · any of")
        PanelLabel("Tags", trailing = "If added · all")
        Text("Cards: bgCard, 1 dp strokeCard inset, radius 10.", style = CrateType.footnote, color = CrateTheme.colors.textSecondary)
        ChipFlow {
            ChipChrome(CrateTheme.colors.fillControl, CrateTheme.colors.textPrimary) { Text("ChipChrome") }
            ChipChrome(CrateTheme.colors.fillControl, CrateTheme.colors.textSecondary, outline = CrateTheme.colors.textTertiary, dashed = true) {
                Text("dashed")
            }
            ChipSeparator()
            CountText(1234, style = CrateType.numeralMedium, color = CrateTheme.colors.textPrimary)
        }
    }
}

@Composable
private fun ButtonDemo() {
    var demo by remember { mutableStateOf<PrimaryButtonState>(PrimaryButtonState.Idle("Send 8 to Now")) }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    Specimen("States") {
        PrimaryButton(PrimaryButtonState.Idle("Send 100 to Now"), onClick = {})
        Caption("IDLE")
        PrimaryButton(PrimaryButtonState.Sending(3.0 / 8, "3/8"), onClick = {})
        Caption("SENDING · DETERMINATE")
        PrimaryButton(
            PrimaryButtonState.Success("Sent 8 tracks to Now", "Listen on Spotify"), onClick = {},
            secondaryTitle = "Send again", secondaryAction = {},
        )
        Caption("SUCCESS")
        PrimaryButton(PrimaryButtonState.Error("Couldn't reach Spotify. Check your connection; nothing in Now was changed.", "Retry"), onClick = {})
        Caption("ERROR")
        PrimaryButton(PrimaryButtonState.Disabled("No tracks to send"), onClick = {})
        Caption("DISABLED")
    }
    Specimen("Live (tap)") {
        PrimaryButton(
            demo,
            onClick = {
                if (demo is PrimaryButtonState.Success) {
                    demo = PrimaryButtonState.Idle("Send 8 to Now")
                } else {
                    job?.cancel()
                    job = scope.launch {
                        for (step in 0..8) {
                            demo = PrimaryButtonState.Sending(step / 8.0, "$step/8")
                            delay(180)
                        }
                        demo = PrimaryButtonState.Success("Sent 8 tracks to Now", "Listen on Spotify")
                    }
                }
            },
            secondaryTitle = "Send again",
            secondaryAction = { demo = PrimaryButtonState.Idle("Send 8 to Now") },
        )
    }
}

@Composable
private fun StyleDemo() {
    var removed by remember { mutableStateOf(true) }
    val colors = CrateTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Specimen("Selected · shades") {
            ChipFlow {
                StyleChip("Deep House", Family.HOUSE, genre = "House", shade = StyleShade.LIGHT)
                StyleChip("House", Family.HOUSE, genre = "House", shade = StyleShade.BASE)
                StyleChip("Tech House", Family.HOUSE, genre = "House", shade = StyleShade.DEEP)
                StyleChip("Breakbeat", canonical = "Electronic---Breakbeat", family = Family.BREAKS_BASS, genre = "Breaks & bass")
                StyleChip("Nu-Disco", Family.DISCO, shade = StyleShade.LIGHT, edited = true)
            }
        }
        Specimen("Off · most used / filters") {
            ChipFlow {
                StyleChip("Techno", Family.TECHNO, variant = StyleChipVariant.Off(412), onClick = {})
                StyleChip("Downtempo", Family.DOWNTEMPO, variant = StyleChipVariant.Off(196), onClick = {})
                StyleChip("Boom Bap", Family.HIP_HOP, variant = StyleChipVariant.Off(41), onClick = {})
            }
        }
        Specimen("Genre summary · OR") {
            ChipFlow {
                GenreSummaryChip(Family.HOUSE, GenreSummary.AllStyles, onClick = {})
                ChipSeparator()
                GenreSummaryChip(Family.HOUSE, GenreSummary.Except(listOf("Garage House")), onClick = {}, onRemove = {})
                ChipSeparator()
                GenreSummaryChip(Family.TECHNO, GenreSummary.Count(4, 9), onClick = {})
                AddChip("Add", onClick = {})
            }
        }
        Specimen("Edit tags · removed") {
            ChipFlow {
                StyleChip("Deep House", Family.HOUSE, shade = StyleShade.LIGHT)
                if (removed) {
                    StyleChip("Tech House", Family.HOUSE, variant = StyleChipVariant.Removed, onUndo = { removed = false })
                } else {
                    StyleChip("Tech House", Family.HOUSE, shade = StyleShade.DEEP, onRemove = { removed = true })
                }
                AddChip("Style", onClick = {})
            }
        }
        Specimen("Family mini chips · 17 + none") {
            ChipFlow(spacing = Space.xs, lineSpacing = Space.xs) {
                Family.entries.forEach { FamilyMiniChip(it) }
            }
        }
        Specimen("Family hues · light / base / deep / outline") {
            Family.entries.forEach { family ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    FamilyDot(family)
                    Text(family.displayName, Modifier.weight(1f), style = CrateType.footnote, color = colors.textPrimary)
                    StyleShade.entries.forEach { shade ->
                        Box(Modifier.size(Space.xxl, Space.xl - Space.xxs).background(familyColor(family, shade, colors.isDark), RoundedCornerShape(Radius.badge)))
                    }
                    Box(Modifier.size(Space.xl - Space.xxs).border(Stroke.outline, familyOutline(family, colors.isDark), RoundedCornerShape(Radius.badge)))
                }
            }
        }
    }
}

@Composable
private fun VibeDemo() {
    val selected = remember { mutableStateListOf("Happy") }
    val vibes = listOf(Triple("Hypnotic", "hypnotic", 147), Triple("Happy", "happy", 58), Triple("Party", "party", 24), Triple("Aggressive", "aggressive", 0))
    Specimen(if (selected.size >= 2) "Vibe · all of" else "Vibe") {
        ChipFlow {
            vibes.forEach { (name, key, count) ->
                VibeChip(
                    name, VibeSymbols.defaults[key] ?: "tag",
                    count = if (selected.isEmpty()) count else maxOf(0, count - selected.size * 9),
                    isSelected = name in selected,
                    onClick = { if (name in selected) selected.remove(name) else selected.add(name) },
                    onEdit = {},
                )
            }
            VibeChip("Melancholic", VibeSymbols.defaults["melancholic"] ?: "tag", count = null, hasSeeds = false, onClick = {})
            AddChip("New vibe", onClick = {})
        }
        Caption("OFF · SELECTED · 0 DIMMED · NO SEEDS (DASHED) · NEW VIBE — TAP TO TOGGLE, LONG-PRESS TO EDIT, COUNTS ROLL")
    }
}

@Composable
private fun TagDemo() {
    val tags = remember { mutableStateListOf("italian", "dub techno") }
    val colors = CrateTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Specimen("Picked tags") {
            ChipFlow {
                tags.toList().forEach { tag -> TagChip(tag, onRemove = { tags.remove(tag) }) }
                if (tags.isEmpty()) AddChip("Reset", onClick = { tags.addAll(listOf("italian", "dub techno")) })
            }
        }
        Specimen("Suggestions · “dub”") {
            val rows = listOf(Triple("dub techno", 22, 28), Triple("dub", 9, 41), Triple("deep dub", 6, 8), Triple("dub house", 2, 7), Triple("dubstep", 0, 11))
            Column(Modifier.fillMaxWidth().crateCard()) {
                rows.forEachIndexed { i, (name, ifAdded, total) ->
                    TagSuggestionRow(name, query = "dub", countIfAdded = ifAdded, libraryTotal = total, onClick = {})
                    if (i < rows.size - 1) HorizontalDivider(color = colors.separator)
                }
            }
            Column(Modifier.fillMaxWidth().crateCard()) { TagUnknownRow("vaporwave") }
        }
    }
}

@Composable
private fun ConfidenceDemo() {
    val a = ConfidenceSource.AUDIO
    val d = ConfidenceSource.DISCOGS
    val l = ConfidenceSource.LASTFM
    val b = ConfidenceSource.BEATPORT
    val all = ConfidenceSummary(Family.HOUSE, 4, 4, true, mapOf(a to listOf(Family.HOUSE), d to listOf(Family.HOUSE), l to listOf(Family.HOUSE), b to listOf(Family.HOUSE)))
    val three = ConfidenceSummary(Family.HOUSE, 3, 4, false, mapOf(a to listOf(Family.TECHNO), d to listOf(Family.HOUSE), l to listOf(Family.HOUSE), b to listOf(Family.HOUSE)))
    val twoOfThree = ConfidenceSummary(Family.TECHNO, 2, 3, true, mapOf(a to listOf(Family.TECHNO), d to listOf(Family.TECHNO), l to listOf(Family.HOUSE)))
    val split = ConfidenceSummary(Family.POP_CANTAUTORI, 1, 2, true, mapOf(a to listOf(Family.POP_CANTAUTORI), d to listOf(Family.DISCO)))
    val single = ConfidenceSummary(Family.DOWNTEMPO, 1, 1, true, mapOf(a to listOf(Family.DOWNTEMPO)))
    val override = ConfidenceSummary(
        Family.DISCO, 3, 4, bySource = mapOf(a to listOf(Family.TECHNO), d to listOf(Family.HOUSE), l to listOf(Family.HOUSE), b to listOf(Family.HOUSE)),
        isOverride = true, sourcesFamily = Family.HOUSE,
    )
    val colors = CrateTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Specimen("How many sources agree") {
            listOf(all, three, twoOfThree, split, single, override).forEach { ConfidenceLine(it, expandable = false) }
        }
        Specimen("Expanded (tap to toggle)") {
            ChipFlow {
                StyleChip("Deep House", Family.HOUSE, genre = "House", shade = StyleShade.LIGHT)
                StyleChip("Disco", Family.DISCO, shade = StyleShade.LIGHT)
            }
            ConfidenceLine(three, expanded = true)
            ConfidenceLine(override)
        }
        Specimen("List markers") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    ConfidenceMarker(ConfidenceMarkerKind.SPLIT); Text("split", style = CrateType.footnote, color = colors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    ConfidenceMarker(ConfidenceMarkerKind.SINGLE); Text("one source", style = CrateType.footnote, color = colors.textSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    ConfidenceMarker(all); Text("(none for agreement)", style = CrateType.footnote, color = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun BPMDemo() {
    val colors = CrateTheme.colors
    Specimen("≈ BPM numeral") {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l + Space.xxs), verticalAlignment = Alignment.Bottom) {
            BPMText(139, approx = true, style = CrateType.numeralHero, color = colors.textPrimary)
            BPMText(139, approx = true, style = CrateType.numeralMedium, color = colors.textPrimary)
            BPMText(139, approx = true, style = CrateType.tagLine, color = colors.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l + Space.xxs), verticalAlignment = Alignment.Bottom) {
            BPMText(124, approx = false, style = CrateType.numeralLarge, color = colors.textPrimary)
            BPMText(96, approx = true, style = CrateType.numeralLarge, color = colors.textPrimary)
        }
        Caption("HERO · MEDIUM · TAG LINE · LARGE · LARGE ≈")
    }
}

@Composable
private fun EnergyDemo() {
    var range by remember { mutableStateOf(6..10) }
    var any by remember { mutableStateOf(EnergyRangeControl.any) }
    val colors = CrateTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Specimen("Input · drag across, tap a block") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PanelLabel("Energy", Modifier.weight(1f))
                Text(
                    if (range == EnergyRangeControl.any) "Any" else "${range.first}–${range.last}",
                    style = CrateType.numeralMedium, color = colors.textPrimary,
                )
            }
            EnergyRangeControl(range, onRangeChange = { range = it })
            Caption("RANGE SET")
            EnergyRangeControl(any, onRangeChange = { any = it })
            Caption("ANY · UNLIT, NUMBERED")
        }
        Specimen("Display") {
            EnergyMeter(7)
            Caption("SINGLE VALUE · TRACK DETAIL")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                EnergyMeter(3, style = EnergyMeterStyle.MICRO)
                EnergyMeter(6, style = EnergyMeterStyle.MICRO)
                EnergyMeter(9, style = EnergyMeterStyle.MICRO)
            }
            Caption("MICRO · TAG LINE")
        }
    }
}

@Composable
private fun SliderDemo() {
    var any by remember { mutableStateOf(60..200) }
    var set by remember { mutableStateOf(128..142) }
    var narrow by remember { mutableStateOf(136..142) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Specimen("Unconstrained") {
            SliderHeader(any, constrained = false)
            RangeSlider(any, onRangeChange = { any = it })
        }
        Specimen("Set · half/double ghosted") {
            SliderHeader(set, constrained = true, hint = "also ${set.first / 2}–${set.last / 2}")
            RangeSlider(set, onRangeChange = { set = it }, ghosts = listOf((set.first / 2)..(set.last / 2)))
        }
        Specimen("Narrow · half-capsule thumbs") {
            SliderHeader(narrow, constrained = true)
            RangeSlider(narrow, onRangeChange = { narrow = it })
        }
    }
}

@Composable
private fun SliderHeader(r: IntRange, constrained: Boolean, hint: String? = null) {
    val colors = CrateTheme.colors
    val color = if (constrained) colors.textPrimary else colors.textTertiary
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
        Text("${r.first}", style = CrateType.numeralHero, color = color)
        Text("–", style = CrateType.numeralHero.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = colors.textTertiary)
        Text("${r.last}", style = CrateType.numeralHero, color = color)
        Spacer(Modifier.weight(1f))
        if (hint != null) Text(hint, Modifier.padding(bottom = Space.s), style = CrateType.footnote, color = colors.textSecondary)
    }
}

@Composable
private fun CamelotDemo() {
    var selection by remember { mutableStateOf<String?>("8A") }
    var compatible by remember { mutableStateOf(true) }
    val colors = CrateTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Specimen("Badges") {
            ChipFlow(spacing = Space.m, lineSpacing = Space.m) {
                CamelotBadge("8A", size = CamelotBadgeStyle.TAG_LINE)
                CamelotBadge("8A", size = CamelotBadgeStyle.SMALL)
                CamelotBadge("8A", size = CamelotBadgeStyle.MEDIUM)
                CamelotBadge("8B", size = CamelotBadgeStyle.MEDIUM)
                CamelotBadge(null, size = CamelotBadgeStyle.MEDIUM)
                CamelotBadge("8A", size = CamelotBadgeStyle.MEDIUM, withCompatible = true)
                CamelotBadge("8A", size = CamelotBadgeStyle.MEDIUM, showsKeyName = true)
                CamelotBadge("8A", size = CamelotBadgeStyle.LARGE, confidence = 0.72)
            }
            Caption("TAG LINE · S · M · B · ANY · COMPATIBLE · KEY NAME · L")
        }
        Specimen("Wheel · tap a key") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CamelotWheel(selection, onSelectionChange = { selection = it }, compatible = compatible, modifier = Modifier.widthIn(max = Metric.wheelReference).fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include compatible keys", Modifier.weight(1f), style = CrateType.body, color = colors.textPrimary)
                Switch(compatible, onCheckedChange = { compatible = it })
            }
        }
    }
}

@Composable
private fun TrackDemo() {
    var liked by remember { mutableStateOf(true) }
    val colors = CrateTheme.colors
    val dark = colors.isDark
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Specimen("Compact tag line") {
            CompactTagLine("Breakbeat", 138, "8A", 7, bpmApprox = true, vibes = listOf("Dark", "Hypnotic"))
            CompactTagLine("Deep House", 124, "8A", 6, marker = ConfidenceMarkerKind.SPLIT)
            CompactTagLine("Cantautori", 96, "5B", 3, marker = ConfidenceMarkerKind.SINGLE, bpmApprox = true)
            CompactTagLine("Techno", 140, "9A", 9, edited = setOf(CompactTagLineField.BPM))
            CompactTagLine("A very long style name that has to truncate", 128, "12B", 8, vibes = listOf("Dreamy", "Uplifting", "Romantic"))
            CompactTagLineNoPreview()
        }
        Specimen("Track rows") {
            Column {
                TrackRow(
                    "Windowlicker", "Aphex Twin", coverTint = familyColor(Family.BREAKS_BASS, dark = dark),
                    tagLine = { CompactTagLine("Breakbeat", 138, "8A", 7, bpmApprox = true, vibes = listOf("Dark", "Hypnotic")) },
                )
                HorizontalDivider(color = colors.separator)
                TrackRow(
                    "Un'estate al mare", "Giuni Russo", coverTint = familyColor(Family.POP_CANTAUTORI, dark = dark),
                    tagLine = { CompactTagLine("Italo Pop", 121, "11B", 7, marker = ConfidenceMarkerKind.SPLIT, bpmApprox = true) },
                )
                HorizontalDivider(color = colors.separator)
                TrackRow(
                    "Energy Flash", "Joey Beltram", coverTint = familyColor(Family.TECHNO, dark = dark),
                    tagLine = { CompactTagLine("Techno", 140, "9A", 9, edited = setOf(CompactTagLineField.BPM)) },
                )
                HorizontalDivider(color = colors.separator)
                TrackRow("Only Shallow", "My Bloody Valentine", tagLine = { CompactTagLineNoPreview() })
                HorizontalDivider(color = colors.separator)
                TrackRow(
                    "Shook Ones, Pt. II", "Mobb Deep", coverTint = familyColor(Family.HIP_HOP, dark = dark),
                    tagLine = { CompactTagLine("Boom Bap", 93, "1A", 5) },
                ) { LikeButton(liked, onClick = { liked = !liked }) }
            }
        }
        Specimen("Covers") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(null, size = Size.coverStrip)
                CoverImage(null, size = Size.coverRow, tint = familyColor(Family.HOUSE, dark = dark))
                CoverImage(null, size = Size.coverRow * 2, radius = Radius.coverLarge, tint = familyColor(Family.DISCO, dark = dark))
            }
        }
    }
}

@Composable
private fun PresetDemo() {
    var active by remember { mutableStateOf("Dark 138+") }
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Specimen("Preset pills") {
            ChipFlow {
                PresetPill("Dark 138+", PresetColor.INK, "moon.fill", onClick = { active = "Dark 138+" }, isSelected = active == "Dark 138+")
                PresetPill("Run 160", PresetColor.RED, "bolt.fill", onClick = { active = "Run 160" }, isSelected = active == "Run 160")
                PresetPill("Sunday 90–110", PresetColor.AMBER, "sun.max.fill", onClick = { active = "" }, isModified = true)
                PresetPill("Warehouse", PresetColor.TEAL, "circle.dotted.circle", onClick = { active = "Warehouse" }, isSelected = active == "Warehouse", onLongClick = {})
            }
            Caption("SELECTED · IDLE · MODIFIED")
        }
        Specimen("Preset colours") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                PresetColor.entries.forEach { PresetTile(it, "music.note") }
            }
        }
        Specimen("Tick ring · tri-state") {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                TickRing(TickState.EMPTY)
                TickRing(TickState.CHECKED)
                TickRing(TickState.MIXED)
            }
            Caption("NONE · ALL · SOME")
        }
    }
}

@Composable
private fun IconsDemo() {
    val colors = CrateTheme.colors
    Specimen("Vibe picker · all mapped names") {
        ChipFlow(spacing = Space.s, lineSpacing = Space.s) {
            VibeSymbols.picker.forEach { name ->
                Box(Modifier.size(Size.minTarget).background(colors.fillControl, RoundedCornerShape(Radius.chip)), contentAlignment = Alignment.Center) {
                    Icon(crateIcon(name), name, Modifier.size(22.dp), tint = colors.textPrimary)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            crateIconNames.sorted().forEach { name ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(crateIcon(name), null, Modifier.size(18.dp), tint = colors.textPrimary)
                    Text(name, style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace), color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun MaterialDemo() {
    var on by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("dub") }
    Specimen("Switch · text field") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", Modifier.weight(1f), style = CrateType.body, color = CrateTheme.colors.textPrimary)
            Switch(on, onCheckedChange = { on = it })
        }
        OutlinedTextField(text, onValueChange = { text = it }, label = { Text("Tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

// endregion
