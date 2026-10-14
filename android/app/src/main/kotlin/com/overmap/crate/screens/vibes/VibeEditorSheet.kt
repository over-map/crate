package com.overmap.crate.screens.vibes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overmap.crate.core.Vibe
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.screens.tracks.TrackDetailSheet
import com.overmap.crate.ui.components.AddChip
import com.overmap.crate.ui.components.ChipFlow
import com.overmap.crate.ui.components.CountText
import com.overmap.crate.ui.components.CoverImage
import com.overmap.crate.ui.components.FamilyMiniChip
import com.overmap.crate.ui.components.TagChip
import com.overmap.crate.ui.components.TagSuggestionRow
import com.overmap.crate.ui.components.TagUnknownRow
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.Stroke
import com.overmap.crate.ui.theme.VibeSymbols
import com.overmap.crate.ui.theme.familyColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Vibe editor sheet (6h–6k), port of iOS `VibeEditorView`. `vibeID == null` creates a new vibe; [seedTrackID]
 * pre-adds a seed. Edits a draft; the preview re-ranks the library off the main thread after every change. Save
 * writes `vibes.json` through `AppModel.editVibes`. Owner: Vibes agent.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VibeEditorSheet(vibeID: String?, seedTrackID: String?, onDismiss: () -> Unit) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val isAX = CrateTheme.isAccessibilitySize
    val index = model.index
    val lookup = remember(index) { VibeLookup(index) }
    val artists = remember(index) { ArtistDirectory(index.tracks) }
    val isNew = vibeID == null

    val original = remember(vibeID) { vibeID?.let { model.snapshot.vibes.vibes[it] } ?: Vibe(name = "", share = VibeSymbols.SHARE_NORMAL) }
    var draft by remember(vibeID, seedTrackID) {
        mutableStateOf(
            if (seedTrackID != null && seedTrackID !in original.seeds)
                original.copy(seeds = original.seeds + seedTrackID, exclude = original.exclude - seedTrackID)
            else original,
        )
    }
    var showIcons by remember { mutableStateOf(isNew) }
    var query by remember { mutableStateOf("") }
    var tagMode by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showAllNever by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(VibePreview()) }
    var shownTrack by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    val hasSeeds = VibeText.hasSeeds(draft)
    val hasChanges = draft != original
    val trimmedName = draft.name.trim()
    val canSave = trimmedName.isNotEmpty() && (hasChanges || isNew)
    val symbol = VibeText.symbol(vibeID, draft)
    val displayName = trimmedName.ifEmpty { "this vibe" }
    val searchVisible = draft.seedTags.isEmpty() || tagMode || showSearch || draft.seeds.isNotEmpty() || draft.artists.isNotEmpty()
    val isNewUntouched = isNew && trimmedName.isEmpty() && draft.artists.isEmpty() && draft.seedTags.isEmpty() &&
        draft.seeds.size <= (if (seedTrackID == null) 0 else 1)
    val blockDismiss = hasChanges && !isNewUntouched
    val block by rememberUpdatedState(blockDismiss && !closing)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { value ->
        if (value == SheetValue.Hidden && block) { confirmDiscard = true; false } else true
    })
    val scope = rememberCoroutineScope()
    fun close() {
        closing = true
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    // Live preview: debounce typing and quick taps, then rank ~1,000 vectors off the main thread.
    LaunchedEffect(draft, index) {
        val vi = index.vibeIndex
        val vibe = draft
        if (vi == null || !VibeText.hasSeeds(vibe)) { if (!preview.isEmpty) preview = VibePreview(); return@LaunchedEffect }
        delay(120)
        preview = withContext(Dispatchers.Default) { VibePreview.compute(vibe, vi) }
    }
    LaunchedEffect(Unit) {
        if (!VibeText.hasSeeds(draft)) { delay(500); runCatching { searchFocus.requestFocus() } }
    }

    fun addSeed(id: String) { draft = draft.copy(seeds = if (id in draft.seeds) draft.seeds else draft.seeds + id, exclude = draft.exclude - id) }
    fun removeSeed(id: String) { draft = draft.copy(seeds = draft.seeds - id) }
    fun never(id: String) { draft = draft.copy(seeds = draft.seeds - id, exclude = if (id in draft.exclude) draft.exclude else listOf(id) + draft.exclude) }
    fun addArtist(name: String) {
        if (draft.artists.none { VibeText.fold(it) == VibeText.fold(name) }) draft = draft.copy(artists = draft.artists + name)
    }
    fun addTag(tag: String) {
        if (tag !in draft.seedTags) draft = draft.copy(seedTags = draft.seedTags + tag)
        query = ""; tagMode = false
    }
    fun startTagSearch() { tagMode = true; query = ""; scope.launch { delay(50); runCatching { searchFocus.requestFocus() } } }

    fun save() {
        if (!canSave) return
        var v = draft.copy(name = trimmedName)
        if (v.icon == null && isNew) v = v.copy(icon = "tag")
        val id = vibeID ?: VibeText.slug(v.name, model.snapshot.vibes.vibes.keys)
        val vibe = v
        model.scope.launch {
            model.editVibes { file ->
                val order = if (file.vibes[id] == null && id !in file.order) file.ordered.map { it.id } + id else file.order
                file.copy(vibes = file.vibes + (id to vibe), order = order)
            }
        }
        close()
    }

    fun delete() {
        val id = vibeID ?: return
        model.scope.launch { model.editVibes { file -> file.copy(vibes = file.vibes - id, order = file.order - id) } }
        close()
    }

    val visibleMembers = if (draft.exclude.isEmpty()) preview.members else draft.exclude.toSet().let { n -> preview.members.filter { it !in n } }
    val explicit = draft.seeds.size + draft.artists.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgSheet,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textTertiary) },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // Toolbar: Cancel · title · Save
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (blockDismiss) confirmDiscard = true else close() }) {
                    Text("Cancel", style = CrateType.body, color = colors.textPrimary)
                }
                Text(
                    if (isNew) "New vibe" else trimmedName.ifEmpty { "Vibe" },
                    Modifier.weight(1f).semantics { heading() }, style = CrateType.headline, color = colors.textPrimary,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = ::save, enabled = canSave) {
                    Text("Save", style = CrateType.headline, color = if (canSave) colors.textPrimary else colors.textTertiary)
                }
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = Space.s, bottom = Space.xl)) {
                // Name + icon
                item("name") {
                    GroupRow(first = true, last = true) {
                        Row(
                            Modifier.padding(start = Space.l, end = Space.s).heightIn(min = 56.dp),
                            horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            VibeSymbolTile(symbol)
                            Box(Modifier.weight(1f)) {
                                if (draft.name.isEmpty()) Text("Vibe name", style = CrateType.body.copy(fontSize = 20.sp), color = colors.textTertiary)
                                BasicTextField(
                                    draft.name, { draft = draft.copy(name = it) },
                                    Modifier.fillMaxWidth().semantics { contentDescription = "Vibe name" },
                                    textStyle = CrateType.body.copy(fontSize = 20.sp, color = colors.textPrimary),
                                    singleLine = !isAX,
                                    cursorBrush = SolidColor(colors.textPrimary),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                )
                            }
                            Row(
                                Modifier.heightIn(min = Size.minTarget).clip(RoundedCornerShape(Radius.control))
                                    .clickable { showIcons = !showIcons }
                                    .semantics(mergeDescendants = true) { contentDescription = if (showIcons) "Hide icons" else "Choose icon"; role = Role.Button }
                                    .padding(horizontal = Space.s),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs),
                            ) {
                                if (!isAX) Text("Icon", style = CrateType.secondary, color = colors.textSecondary)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp).rotate(if (showIcons) 90f else 0f), tint = colors.textSecondary)
                            }
                        }
                    }
                }
                if (showIcons) {
                    item("icons") {
                        LazyRow(
                            Modifier.padding(top = Space.m), contentPadding = PaddingValues(horizontal = Space.screenMargin),
                            horizontalArrangement = Arrangement.spacedBy(Space.s - Space.xxs),
                        ) {
                            items(VibeSymbols.picker) { name ->
                                val on = name == symbol
                                VibeSymbolTile(
                                    name,
                                    Modifier.clip(RoundedCornerShape(Radius.control)).clickable { draft = draft.copy(icon = name) }
                                        .semantics { contentDescription = VibeText.symbolLabel(name); selected = on; role = Role.Button },
                                    size = Size.minTarget, selected = on, dimmed = true,
                                )
                            }
                        }
                    }
                }

                // Seeds
                item("seeds-header") {
                    VibeSectionHeader(
                        if (explicit > 0 || draft.seedTags.isEmpty()) "Seeds · $explicit" else "Seeds",
                        trailing = if (isAX) null else when {
                            explicit == 0 && draft.seedTags.isNotEmpty() -> "From community tags"
                            explicit < 2 -> "Add 2–5"
                            else -> null
                        },
                    )
                }
                if (!hasSeeds) {
                    item("empty") {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.s).semantics(mergeDescendants = true) {},
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            VibeSymbolTile(symbol, Modifier.padding(bottom = Space.xs), size = 44.dp)
                            Text("No seeds yet", style = CrateType.headline, color = colors.textPrimary)
                            Text(
                                "Add 2–5 tracks or artists that are this vibe. Crate finds the tracks that sound closest.",
                                style = CrateType.secondary, color = colors.textSecondary, textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(Space.m))
                    }
                }
                if (draft.seedTags.isNotEmpty()) {
                    item("tags") {
                        Column(Modifier.padding(horizontal = Space.screenMargin, vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            ChipFlow {
                                draft.seedTags.forEach { tag -> TagChip(tag, onRemove = { draft = draft.copy(seedTags = draft.seedTags - tag) }) }
                                Box(
                                    Modifier.heightIn(min = Size.minTarget).clickable(onClick = ::startTagSearch)
                                        .semantics { contentDescription = "Add community tag"; role = Role.Button },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier.size(width = Size.chipHeight + Space.s, height = Size.chipHeight)
                                            .background(colors.fillControl, RoundedCornerShape(Radius.chip)),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp), tint = colors.textPrimary) }
                                }
                            }
                            val n = preview.tagSeedCount
                            val lead = if (n == 1) "1 seed track: artists the community tags this way"
                                else "${VibeText.count(n)} seed tracks: artists the community tags this way"
                            if (searchVisible) {
                                Text(lead, style = CrateType.footnote, color = colors.textSecondary)
                            } else {
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = colors.textSecondary)) { append("$lead · ") }
                                        withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.Medium)) { append("＋ Tracks or artists") }
                                    },
                                    Modifier.heightIn(min = Size.minTarget).clickable {
                                        showSearch = true
                                        scope.launch { delay(50); runCatching { searchFocus.requestFocus() } }
                                    }.semantics { role = Role.Button },
                                    style = CrateType.footnote,
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.s))
                    }
                }
                if (searchVisible) {
                    item("search") {
                        GroupRow(first = true, last = true, fill = colors.fillControl) {
                            Row(
                                Modifier.heightIn(min = Size.minTarget).padding(start = Space.m, end = Space.xxs),
                                horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (tagMode) Icons.Filled.Tag else Icons.Filled.Search, null, Modifier.size(18.dp), tint = colors.textTertiary)
                                Box(Modifier.weight(1f)) {
                                    val hint = if (tagMode) "Add a community tag" else "Add tracks or artists"
                                    if (query.isEmpty()) Text(hint, style = CrateType.body, color = colors.textTertiary, maxLines = 1)
                                    BasicTextField(
                                        query, { query = it },
                                        Modifier.fillMaxWidth().focusRequester(searchFocus).semantics { contentDescription = hint },
                                        textStyle = CrateType.body.copy(color = colors.textPrimary), singleLine = true,
                                        cursorBrush = SolidColor(colors.textPrimary),
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                                    )
                                }
                                if (query.isNotEmpty() || tagMode) {
                                    Box(
                                        Modifier.size(Size.minTarget).clip(CircleShape).clickable { query = ""; if (tagMode) tagMode = false }
                                            .semantics { contentDescription = if (tagMode) "Cancel tag search" else "Clear search"; role = Role.Button },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.Filled.Cancel, null, Modifier.size(18.dp), tint = colors.textTertiary) }
                                } else Spacer(Modifier.height(Size.minTarget))
                            }
                        }
                        Spacer(Modifier.height(Space.m))
                    }
                }
                val q = query.trim()
                if (q.isNotEmpty()) {
                    if (tagMode) {
                        val hits = index.tagSuggestions(q, 6).filter { it.first !in draft.seedTags }
                        if (hits.isEmpty()) {
                            item("tag-none") { GroupRow(true, true) { TagUnknownRow(q) }; Spacer(Modifier.height(Space.m)) }
                        } else {
                            itemsIndexed(hits, key = { _, h -> "tag-" + h.first }) { i, hit ->
                                GroupRow(i == 0, i == hits.lastIndex) {
                                    TagSuggestionRow(hit.first, q, countIfAdded = null, libraryTotal = hit.second, onClick = { addTag(hit.first) })
                                }
                                if (i == hits.lastIndex) Spacer(Modifier.height(Space.m))
                            }
                        }
                    } else {
                        val artistHits = artists.search(q, 3).filter { a -> draft.artists.none { VibeText.fold(it) == a.folded } }
                        val trackHits = searchTracks(q, 6, index.ids, draft.seeds.toSet(), lookup)
                        val total = artistHits.size + trackHits.size
                        if (total == 0) {
                            item("search-none") {
                                GroupRow(true, true) {
                                    Box(Modifier.heightIn(min = Size.minTarget).padding(horizontal = Space.l), contentAlignment = Alignment.CenterStart) {
                                        Text("Nothing in your library matches “$q”", style = CrateType.secondary, color = colors.textSecondary)
                                    }
                                }
                                Spacer(Modifier.height(Space.m))
                            }
                        }
                        itemsIndexed(artistHits, key = { _, a -> "artist-hit-" + a.folded }) { i, a ->
                            GroupRow(i == 0, i == total - 1) {
                                ResultRow(a.name, if (a.count == 1) "Artist · 1 track" else "Artist · ${a.count} tracks", onAdd = { addArtist(a.name); query = "" }) {
                                    InitialsCircle(a.name)
                                }
                            }
                            if (i == total - 1) Spacer(Modifier.height(Space.m))
                        }
                        itemsIndexed(trackHits, key = { _, id -> "track-hit-$id" }) { j, id ->
                            val i = artistHits.size + j
                            GroupRow(i == 0, i == total - 1) {
                                ResultRow(lookup.title(id), "Track · ${lookup.artist(id)}", onAdd = { addSeed(id); query = "" }) {
                                    CoverImage(lookup.cover(id), size = 32.dp, tint = lookup.family(id)?.let { familyColor(it, dark = colors.isDark) })
                                }
                            }
                            if (i == total - 1) Spacer(Modifier.height(Space.m))
                        }
                    }
                }
                if (explicit > 0) {
                    val rows = draft.artists.map { "a:$it" } + draft.seeds.map { "t:$it" }
                    itemsIndexed(rows, key = { _, k -> "seed-$k" }) { i, key ->
                        GroupRow(i == 0, i == rows.lastIndex, dividerInset = Space.l + 32.dp + Space.m) {
                            if (key.startsWith("a:")) {
                                val name = key.drop(2)
                                val c = artists.count(name)
                                val tracks = if (c == 1) "1 track" else "$c tracks"
                                SeedRow(
                                    "Artist $name, $tracks",
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = colors.textPrimary)) { append(name) }
                                        withStyle(SpanStyle(color = colors.textSecondary, fontSize = 13.sp)) { append(" · artist · $tracks") }
                                    },
                                    onRemove = { draft = draft.copy(artists = draft.artists - name) },
                                ) { InitialsCircle(name) }
                            } else {
                                val id = key.drop(2)
                                SeedRow(
                                    "${lookup.title(id)} by ${lookup.artist(id)}",
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = colors.textPrimary)) { append(lookup.title(id)) }
                                        withStyle(SpanStyle(color = colors.textSecondary)) { append(" · ${lookup.artist(id)}") }
                                    },
                                    onRemove = { removeSeed(id) },
                                ) { CoverImage(lookup.cover(id), size = 32.dp, tint = lookup.family(id)?.let { familyColor(it, dark = colors.isDark) }) }
                            }
                        }
                        if (i == rows.lastIndex) Spacer(Modifier.height(Space.m))
                    }
                }
                if (draft.seedTags.isEmpty() && !tagMode) {
                    item("add-tag") {
                        Box(Modifier.padding(horizontal = Space.screenMargin, vertical = Space.xs)) {
                            AddChip("Community tag", onClick = ::startTagSearch)
                        }
                    }
                }

                // Never
                if (draft.exclude.isNotEmpty()) {
                    item("never-header") { VibeSectionHeader("Never · ${draft.exclude.size}") }
                    val visible = if (showAllNever) draft.exclude else draft.exclude.take(2)
                    val more = draft.exclude.size > 2
                    itemsIndexed(visible, key = { _, id -> "never-$id" }) { i, id ->
                        GroupRow(i == 0, !more && i == visible.lastIndex) {
                            val allow = { draft = draft.copy(exclude = draft.exclude - id) }
                            Row(
                                Modifier.heightIn(min = Size.minTarget).padding(start = Space.l, end = Space.s)
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "Never: ${lookup.title(id)} by ${lookup.artist(id)}"
                                        customActions = listOf(CustomAccessibilityAction("Allow again") { allow(); true })
                                    },
                                horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Block, null, Modifier.size(16.dp), tint = colors.textSecondary)
                                Text(
                                    buildAnnotatedString {
                                        withStyle(SpanStyle(color = colors.textPrimary)) { append(lookup.title(id)) }
                                        withStyle(SpanStyle(color = colors.textSecondary)) { append(" · ${lookup.artist(id)}") }
                                    },
                                    Modifier.weight(1f), style = CrateType.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                VibeMinusButton("Allow ${lookup.title(id)} again", allow)
                            }
                        }
                    }
                    if (more) {
                        item("never-more") {
                            GroupRow(first = false, last = true) {
                                Box(
                                    Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable { showAllNever = !showAllNever }
                                        .semantics { role = Role.Button }.padding(horizontal = Space.l),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(if (showAllNever) "Show less" else "Show ${draft.exclude.size - 2} more", style = CrateType.body, color = colors.textSecondary)
                                }
                            }
                        }
                    }
                }

                // Share
                item("share") {
                    VibeSectionHeader("How much of the library")
                    val shares = VibeShare.entries
                    VibeSegmented(
                        shares.map { it.label }, VibeShare.of(draft.share)?.ordinal,
                        onSelect = { draft = draft.copy(share = shares[it].value) },
                        modifier = Modifier.padding(horizontal = Space.screenMargin)
                            .semantics { contentDescription = "How much of the library" },
                    )
                }

                // Preview
                if (hasSeeds) {
                    item("preview-header") {
                        VibeSectionHeader(
                            "Preview · ${VibeText.count(visibleMembers.size)} tracks",
                            trailing = if (isAX) null else if (isNew && draft.exclude.isEmpty()) "Closest first" else "Swipe ← never",
                        )
                    }
                    if (preview.isEmpty) {
                        item("preview-empty") {
                            GroupRow(true, true) {
                                Box(Modifier.heightIn(min = Size.minTarget).padding(horizontal = Space.l), contentAlignment = Alignment.CenterStart) {
                                    Text(
                                        if (index.vibeIndex == null) "Sound fingerprints aren't loaded yet." else "Finding the closest tracks…",
                                        style = CrateType.secondary, color = colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(visibleMembers, key = { _, id -> "p-$id" }) { i, id ->
                        GroupRow(i == 0, i == visibleMembers.lastIndex, dividerInset = Space.l + 24.dp + Space.m) {
                            PreviewRow(
                                rank = i + 1, id = id, lookup = lookup, isSeed = id in draft.seeds, symbolName = symbol,
                                displayName = displayName,
                                onNever = { never(id) },
                                onToggleSeed = { if (id in draft.seeds) removeSeed(id) else addSeed(id) },
                                onShow = { shownTrack = id },
                            )
                        }
                    }
                    if (!preview.isEmpty) {
                        item("preview-footer") {
                            Text(
                                "“Never” also pushes tracks that sound like it down the list.",
                                Modifier.padding(horizontal = Space.screenMargin + Space.l, vertical = Space.s),
                                style = CrateType.footnote, color = colors.textTertiary,
                            )
                        }
                    }
                }

                // Delete (existing vibes)
                if (!isNew) {
                    item("delete") {
                        Spacer(Modifier.height(Space.l))
                        GroupRow(true, true) {
                            Box(
                                Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable { confirmDelete = true }
                                    .semantics { role = Role.Button }.padding(horizontal = Space.l),
                                contentAlignment = Alignment.Center,
                            ) { Text("Delete vibe", style = CrateType.body, color = colors.error) }
                        }
                    }
                }
            }

            // Bottom bar: live count + Save
            val total = maxOf(1, index.tracks.size)
            val n = visibleMembers.size
            val pct = String.format(Locale.getDefault(), "%.1f", n.toDouble() / total * 100)
            val shareName = VibeShare.of(draft.share)?.title ?: "Custom"
            Column(Modifier.fillMaxWidth().background(colors.bgSheet)) {
                Box(Modifier.fillMaxWidth().height(Stroke.hairline).background(colors.separator))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.screenMargin, vertical = Space.m).navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically,
                ) {
                    val countLine = if (hasSeeds) "$pct% of the library · $shareName" else "Add seeds to see tracks"
                    Column(
                        Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${if (hasSeeds) n else 0} tracks, $countLine" },
                        verticalArrangement = Arrangement.spacedBy(Space.xxs),
                    ) {
                        CountText(if (hasSeeds) n else 0, style = CrateType.numeralLarge, color = if (hasSeeds) colors.textPrimary else colors.textTertiary)
                        Text(countLine, style = CrateType.footnote, color = colors.textSecondary, maxLines = if (isAX) 3 else 2)
                    }
                    // At accessibility sizes the toolbar Save is enough; the bar keeps only the live count.
                    if (!isAX) {
                        Box(
                            Modifier.heightIn(min = Size.buttonHeight).widthIn(min = 120.dp)
                                .clip(RoundedCornerShape(Radius.button))
                                .background(if (canSave) colors.accent else colors.fillControl)
                                .clickable(enabled = canSave, onClick = ::save)
                                .semantics { role = Role.Button; contentDescription = "Save" }
                                .padding(horizontal = Space.xxl + Space.s),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Save", style = CrateType.headline, color = if (canSave) colors.onAccent else colors.textTertiary)
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = colors.bgCard,
            title = { Text("Discard changes to $displayName?", style = CrateType.headline, color = colors.textPrimary) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; close() }) { Text("Discard changes", color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", color = colors.textPrimary) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = colors.bgCard,
            title = { Text("Delete $displayName?", style = CrateType.headline, color = colors.textPrimary) },
            text = { Text("Presets that use it will skip it. This can't be undone.", style = CrateType.secondary, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; delete() }) { Text("Delete vibe", color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = colors.textPrimary) }
            },
        )
    }
    shownTrack?.let { id -> TrackDetailSheet(trackID = id, onDismiss = { shownTrack = null }) }
}

/** Title matches (prefix first, then substring, each by title), excluding current seeds. */
private fun searchTracks(q: String, limit: Int, ids: List<String>, seeds: Set<String>, lookup: VibeLookup): List<String> {
    val f = VibeText.fold(q)
    if (f.isEmpty()) return emptyList()
    val prefix = mutableListOf<String>()
    val contains = mutableListOf<String>()
    for (id in ids) {
        if (id in seeds) continue
        val t = lookup.index.tracks[id] ?: continue
        val title = VibeText.fold(t.title ?: "")
        if (title.startsWith(f)) prefix += id else if (title.contains(f)) contains += id
    }
    val byTitle = compareBy<String> { lookup.title(it) }
    return (prefix.sortedWith(byTitle) + contains.sortedWith(byTitle)).take(limit)
}

/** A search hit: leading art · title / subtitle · accent "＋". The whole row adds. */
@Composable
private fun ResultRow(title: String, subtitle: String, onAdd: () -> Unit, leading: @Composable () -> Unit) {
    val colors = CrateTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTarget + Space.s).clickable(onClick = onAdd)
            .clearAndSetSemantics { contentDescription = "Add $title, $subtitle"; role = Role.Button }
            .padding(horizontal = Space.l, vertical = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(title, style = CrateType.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = CrateType.footnote, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(28.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = colors.onAccent)
        }
    }
}

/** A seed (artist or track) with its "−" remove control. */
@Composable
private fun SeedRow(label: String, text: androidx.compose.ui.text.AnnotatedString, onRemove: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTarget + Space.xs).padding(start = Space.l, end = Space.s)
            .semantics(mergeDescendants = true) {
                contentDescription = "Seed: $label"
                customActions = listOf(CustomAccessibilityAction("Remove") { onRemove(); true })
            },
        horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(text, Modifier.weight(1f), style = CrateType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        VibeMinusButton("Remove $label", onRemove)
    }
}

/** A ranked preview member. Swipe ← marks it Never; tap or long-press opens seed / never / show actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PreviewRow(
    rank: Int, id: String, lookup: VibeLookup, isSeed: Boolean, symbolName: String, displayName: String,
    onNever: () -> Unit, onToggleSeed: () -> Unit, onShow: () -> Unit,
) {
    val colors = CrateTheme.colors
    val title = lookup.title(id)
    val artist = lookup.artist(id)
    val family = lookup.family(id)
    var menu by remember { mutableStateOf(false) }
    val currentNever by rememberUpdatedState(onNever)
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) currentNever() },
        backgroundContent = {
            if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) Row(
                Modifier.fillMaxSize().background(colors.accent).padding(horizontal = Space.xl),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    Icon(Icons.Filled.Block, null, Modifier.size(18.dp), tint = colors.onAccent)
                    Text("Never", style = CrateType.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.onAccent)
                }
            }
        },
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth().background(colors.bgCard)
                    .combinedClickable(onClick = { menu = true }, onLongClick = { menu = true }, onClickLabel = "Actions")
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$rank. $title by $artist${if (isSeed) ", seed" else ""}"
                        customActions = listOf(
                            CustomAccessibilityAction("Never in $displayName") { onNever(); true },
                            CustomAccessibilityAction(if (isSeed) "Remove seed" else "Add as seed") { onToggleSeed(); true },
                            CustomAccessibilityAction("Show track") { onShow(); true },
                        )
                    }
                    .padding(horizontal = Space.l, vertical = Space.s - Space.xxs),
                horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$rank", Modifier.widthIn(min = 24.dp),
                    style = CrateType.footnote.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    color = colors.textTertiary, textAlign = TextAlign.End,
                )
                CoverImage(lookup.cover(id), size = 40.dp, tint = family?.let { familyColor(it, dark = colors.isDark) })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    Text(title, style = CrateType.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artist, style = CrateType.secondary, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (isSeed) Text("Seed", style = CrateType.footnote.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = colors.textSecondary)
                if (family != null) FamilyMiniChip(family)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.bgCard) {
                DropdownMenuItem(
                    text = { Text(if (isSeed) "Remove seed" else "Add as seed", color = colors.textPrimary) },
                    leadingIcon = {
                        if (isSeed) Icon(Icons.Outlined.RemoveCircleOutline, null, tint = colors.textPrimary)
                        else Icon(com.overmap.crate.ui.theme.crateIcon(symbolName), null, tint = colors.textPrimary)
                    },
                    onClick = { menu = false; onToggleSeed() },
                )
                DropdownMenuItem(
                    text = { Text("Never in $displayName", color = colors.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.Block, null, tint = colors.textPrimary) },
                    onClick = { menu = false; onNever() },
                )
                DropdownMenuItem(
                    text = { Text("Show track", color = colors.textPrimary) },
                    leadingIcon = { Icon(Icons.Filled.MusicNote, null, tint = colors.textPrimary) },
                    onClick = { menu = false; onShow() },
                )
            }
        }
    }
}
