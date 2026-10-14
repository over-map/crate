package com.overmap.crate.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.overmap.crate.MainActivity
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.core.CrateLink
import com.overmap.crate.crateModel
import com.overmap.crate.ui.components.ChipFlow
import com.overmap.crate.ui.components.CompactTagLine
import com.overmap.crate.ui.components.ConfidenceLine
import com.overmap.crate.ui.components.CoverImage
import com.overmap.crate.ui.components.PrimaryButton
import com.overmap.crate.ui.components.PrimaryButtonState
import com.overmap.crate.ui.components.VibeChip
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.crateIcon
import com.overmap.crate.ui.theme.familyColor
import kotlinx.coroutines.launch

/**
 * Spotify → Share → Crate (Swift: `ShareViewController`). Receives `ACTION_SEND` text/plain (a track link, or
 * text containing one) and shows [ShareSheet] as a bottom sheet over the sharing app.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DebugLaunch.read(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = crateModel
        if (DebugLaunch.isDemo && !app.isDemo) app.useDemoData()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val model = ShareModel(applicationContext, app)
        setContent {
            CrateTheme {
                LaunchedEffect(Unit) { model.load(text) }
                ShareSheet(model, open = ::open, openNow = { app.openNow(); finish() }, close = ::finish)
            }
        }
    }

    /** Opens a `crate://` link in the app, then closes the sheet. */
    private fun open(link: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link), this, MainActivity::class.java)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** The share sheet (design 6s in library, 3m not in library, 3n not a track). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(model: ShareModel, open: (String) -> Unit, openNow: () -> Unit, close: () -> Unit) {
    val colors = CrateTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { close() } }
    ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = colors.bgBase) {
        Column(Modifier.navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.xs)) {
                IconButton(onClick = dismiss, Modifier.align(Alignment.CenterStart)) {
                    Icon(crateIcon("xmark"), "Close", tint = colors.textPrimary)
                }
                Text("Crate", Modifier.align(Alignment.Center).semantics { heading() },
                     style = CrateType.headline, color = colors.textPrimary)
            }
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.screenMargin, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
            ) {
                Content(model, open, openNow)
            }
        }
    }
}

@Composable
private fun Content(model: ShareModel, open: (String) -> Unit, openNow: () -> Unit) {
    when (val phase = model.phase) {
        ShareModel.Phase.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CrateTheme.colors.textSecondary)
        }
        is ShareModel.Phase.InLibrary -> InLibrary(model, phase.card, open, openNow)
        is ShareModel.Phase.NotInLibrary -> NotInLibrary(model, phase.id, phase.liked)
        ShareModel.Phase.NotATrack -> Message(
            "music.note", "Not a Spotify track",
            "Share a track from Spotify (⋯ → Share → Crate) to see its tags or send similar music to Now.",
        )
        is ShareModel.Phase.NoData -> {
            Message("arrow.down.circle", "Open Crate once",
                    "Crate hasn't loaded your library on this phone yet. Open it once, then share again.")
            Secondary("Open in Crate", "arrow.up.forward.app") { open(CrateLink.track(phase.id)) }
        }
    }
}

// MARK: In library (6s)

@Composable
private fun InLibrary(model: ShareModel, card: ShareModel.TrackCard, open: (String) -> Unit, openNow: () -> Unit) {
    val colors = CrateTheme.colors
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Column(Modifier.fillMaxWidth().crateCard().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(card.coverURL, size = 56.dp, radius = Radius.cover, tint = familyColor(card.family, dark = colors.isDark))
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    Text(card.title, style = CrateType.headline, color = colors.textPrimary, maxLines = 2)
                    Text(card.artist, style = CrateType.secondary, color = colors.textSecondary, maxLines = 1)
                }
            }
            CompactTagLine(card.track, card.styleName)
            card.confidence?.let { ConfidenceLine(it, expandable = false) }
            if (card.vibes.isNotEmpty()) {
                ChipFlow(Modifier.clearAndSetSemantics {
                    contentDescription = "Vibes: " + card.vibes.joinToString(", ") { it.name }
                }) {
                    card.vibes.forEach { v -> VibeChip(v.name, v.icon, count = null, onClick = {}) }
                }
            }
        }

        PrimaryButton(sendState(model), onClick = {
            when (model.action) {
                is ShareModel.Action.Done -> openNow()
                else -> scope.launch { model.sendMoreLikeThis() }
            }
        })
        Secondary("Edit in Crate", "pencil") { open(CrateLink.track(card.id)) }
        Text(
            "More like this sends the 100 most similar tracks, shuffled. Edit opens the track in Crate, where you change its tags and vibes.",
            style = CrateType.footnote, color = colors.textSecondary,
        )
    }
}

private fun sendState(model: ShareModel): PrimaryButtonState {
    if (!model.canSendMoreLikeThis) return PrimaryButtonState.Disabled("No sound data yet")
    return when (val a = model.action) {
        ShareModel.Action.Idle -> PrimaryButtonState.Idle("More like this → Now")
        ShareModel.Action.Working -> PrimaryButtonState.Sending(0.5)
        is ShareModel.Action.Done -> PrimaryButtonState.Success(a.text, "Listen on Spotify")
        is ShareModel.Action.Failed -> PrimaryButtonState.Error(a.text, "Try again")
    }
}

// MARK: Not in library (3m)

@Composable
private fun NotInLibrary(model: ShareModel, id: String, liked: Boolean) {
    val scope = rememberCoroutineScope()
    if (liked) {
        Message("heart.fill", "Liked", "It's in your Liked Songs. Crate tags it tonight, and it shows up in Build tomorrow.")
    } else {
        Message("heart", "Not in your library yet", "Like it and Crate tags it tonight.")
        val state = when (val a = model.action) {
            ShareModel.Action.Working -> PrimaryButtonState.Sending(0.5)
            is ShareModel.Action.Failed -> PrimaryButtonState.Error(a.text, "Try again")
            else -> PrimaryButtonState.Idle("♡ Like in Spotify")
        }
        PrimaryButton(state, onClick = { scope.launch { model.like(id) } })
    }
}

// MARK: Pieces

@Composable
private fun Message(symbol: String, title: String, text: String) {
    val colors = CrateTheme.colors
    Column(
        Modifier.fillMaxWidth().crateCard().padding(Space.l).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(crateIcon(symbol), null, Modifier.size(24.dp), tint = colors.textSecondary)
        Text(title, style = CrateType.headline, color = colors.textPrimary)
        Text(text, style = CrateType.secondary, color = colors.textSecondary)
    }
}

@Composable
private fun Secondary(title: String, symbol: String, onClick: () -> Unit) {
    val colors = CrateTheme.colors
    val shape = remember { RoundedCornerShape(Radius.button) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(colors.fillControl, shape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; onClick(label = title) { onClick(); true } },
        horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(crateIcon(symbol), null, Modifier.size(18.dp), tint = colors.textPrimary)
        Text(title, style = CrateType.chipLabel, color = colors.textPrimary)
    }
}
