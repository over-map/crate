package com.overmap.crate.screens.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.overmap.crate.BuildConfig
import com.overmap.crate.app.AppModel
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.app.DemoDataSource
import com.overmap.crate.core.GitHubError
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.PanelLabel
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect

/**
 * Onboarding, 3 steps (design 3a–3c): connect Spotify, connect the GitHub data repo, optional ntfy notifications.
 * Ends with `model.phase = Loading`. Port of iOS `OnboardingView`.
 */
@Composable
fun OnboardingScreen(startStep: Int = 1) {
    val model = rememberModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = CrateTheme.colors
    val prefs = remember { context.getSharedPreferences("crate", Context.MODE_PRIVATE) }

    var step by rememberSaveable { mutableStateOf(startStep.coerceIn(1, 3)) }
    var spotifyConnected by remember { mutableStateOf(model.spotify.isConnected) }
    var connecting by remember { mutableStateOf(false) }
    var spotifyError by remember { mutableStateOf<String?>(null) }
    var token by rememberSaveable { mutableStateOf("") }
    var check by remember { mutableStateOf<TokenCheck>(TokenCheck.Idle) }
    var whatsThisOpen by rememberSaveable { mutableStateOf(false) }
    var topic by rememberSaveable { mutableStateOf(prefs.getString("ntfy.topic", null) ?: "") }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (topic.isEmpty()) { topic = randomTopic(); prefs.edit().putString("ntfy.topic", topic).apply() }
        when (DebugLaunch.screen) {
            "onboarding", "onboarding-1" -> step = 1
            "onboarding-2" -> {
                step = 2
                whatsThisOpen = true
                token = "github_pat_11ADEMO0example0token0for0screens"
                // Validate against whatever source is set (the demo data with `demo`) without saving the fake token.
                check = TokenCheck.Checking
                check = try { TokenCheck.Ok(model.validateGitHub()) } catch (e: Exception) { TokenCheck.Failed(TokenCheck.message(e)) }
            }
            "onboarding-3" -> step = 3
        }
    }

    // The login tab was closed without a callback: stop waiting once Crate is back in front.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && connecting) {
                scope.launch {
                    delay(1500)
                    if (connecting && !model.spotify.isConnected) model.spotify.cancelPending()
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun connectSpotify() {
        connecting = true
        spotifyError = null
        scope.launch {
            try {
                model.spotify.connect(context)
                spotifyConnected = model.spotify.isConnected
                if (spotifyConnected) step = 2
            } catch (_: CancellationException) {
                // Login closed: no error.
            } catch (_: Exception) {
                spotifyError = "Couldn't connect to Spotify. Check your connection and try again."
            }
            connecting = false
        }
    }

    suspend fun validate(): Boolean {
        val t = token.trim()
        if (t.isEmpty()) return false
        check = TokenCheck.Checking
        return try {
            model.saveGitHubToken(t)
            check = TokenCheck.Ok(model.validateGitHub())
            true
        } catch (e: Exception) {
            check = TokenCheck.Failed(TokenCheck.message(e))
            false
        }
    }

    val finish = { model.phase = AppModel.Phase.Loading }
    BackHandler(enabled = step > 1) { step -= 1 }

    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.screenMargin).padding(top = Space.s).heightIn(min = Size.minTarget),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 1) {
                Row(
                    Modifier.heightIn(min = Size.minTarget).clickable(role = Role.Button) { step -= 1 },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = c.textSecondary)
                    Text("Back", style = CrateType.body, color = c.textSecondary)
                }
            }
            Row(
                Modifier.clearAndSetSemantics { contentDescription = "Step $step of 3" },
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 1..3) {
                    Box(Modifier.size(width = 18.dp, height = 3.dp).background(if (i <= step) c.accent else c.fillControlActive, RoundedCornerShape(2.dp)))
                }
                Text("STEP $step OF 3", style = CrateType.panelLabel, color = c.textSecondary, modifier = Modifier.padding(start = Space.xs))
            }
            Spacer(Modifier.weight(1f))
            if (step == 3) {
                Box(Modifier.heightIn(min = Size.minTarget).clickable(role = Role.Button, onClick = finish), contentAlignment = Alignment.Center) {
                    Text("Skip", style = CrateType.body, color = c.textSecondary)
                }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Space.screenMargin).padding(top = Space.xl, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
        ) {
            when (step) {
                1 -> SpotifyStep(spotifyConnected, spotifyError)
                2 -> DataStep(
                    token = token,
                    onToken = { token = it; if (check != TokenCheck.Checking) check = TokenCheck.Idle },
                    onSubmit = { scope.launch { validate() } },
                    check = check, whatsThisOpen = whatsThisOpen, onWhatsThis = { whatsThisOpen = !whatsThisOpen },
                )
                else -> NotificationsStep(
                    topic = topic,
                    onTopic = { topic = it; copied = false; prefs.edit().putString("ntfy.topic", it).apply() },
                    copied = copied,
                    onCopy = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("ntfy topic", topic))
                        copied = true
                    },
                    onOpen = {
                        val t = topic.trim()
                        openURL(context, "https://ntfy.sh/" + android.net.Uri.encode(t))
                    },
                )
            }
        }

        // Bottom bar
        Column(
            Modifier.fillMaxWidth().background(c.bgBase).padding(horizontal = Space.screenMargin).padding(top = Space.m, bottom = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (step) {
                1 -> {
                    if (spotifyConnected) {
                        LargeButton(onClick = { step = 2 }) { Text("Continue") }
                    } else {
                        LargeButton(onClick = ::connectSpotify, enabled = !connecting) {
                            if (connecting) CircularProgressIndicator(Modifier.size(18.dp), color = c.onAccent, strokeWidth = 2.dp)
                            Text("Connect Spotify")
                        }
                    }
                    Text(
                        "Opens Spotify to sign in. Crate never posts or follows.", style = CrateType.footnote,
                        color = c.textTertiary, textAlign = TextAlign.Center,
                    )
                    DemoButton(model)
                }
                2 -> {
                    LargeButton(
                        onClick = {
                            if (check is TokenCheck.Ok) step = 3 else scope.launch { if (validate()) step = 3 }
                        },
                        enabled = token.isNotBlank() && check != TokenCheck.Checking,
                    ) {
                        if (check == TokenCheck.Checking) CircularProgressIndicator(Modifier.size(18.dp), color = c.onAccent, strokeWidth = 2.dp)
                        Text("Continue")
                    }
                    DemoButton(model)
                }
                else -> LargeButton(onClick = finish) { Text("Done") }
            }
        }
    }
}

@Composable
private fun DemoButton(model: AppModel) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    if (!remember { DemoDataSource.isAvailable(context) }) return
    Box(Modifier.heightIn(min = Size.minTarget).clickable(role = Role.Button) { model.useDemoData() }, contentAlignment = Alignment.Center) {
        Text("Use demo data", style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold), color = CrateTheme.colors.textSecondary)
    }
}

private val titleStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)

@Composable
private fun SpotifyStep(connected: Boolean, error: String?) {
    val c = CrateTheme.colors
    Row(
        Modifier.padding(top = Space.xl, bottom = Space.l),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).background(c.accent, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Layers, null, Modifier.size(30.dp), tint = c.onAccent)
        }
        Text("Crate", style = CrateType.screenTitle, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    }
    Text("Your liked songs, sorted by what they sound like.", style = titleStyle, color = c.textPrimary)
    Text("Pick a genre, a vibe and a tempo, then send the matches to Spotify.", style = CrateType.body, color = c.textSecondary)
    Column(Modifier.fillMaxWidth().crateCard()) {
        PermissionRow("Reads", buildAnnotatedString { append("Your Liked Songs") })
        RowSeparator()
        PermissionRow("Writes", buildAnnotatedString {
            append("Only two playlists: ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Now") }
            append(" and ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Crate Daily") }
        })
    }
    if (connected) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = c.success)
            Text("Spotify connected", style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold), color = c.success)
        }
    }
    if (error != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = c.error)
            Text(error, style = CrateType.secondary, color = c.error)
        }
    }
}

@Composable
private fun PermissionRow(label: String, text: androidx.compose.ui.text.AnnotatedString) {
    val c = CrateTheme.colors
    val content: @Composable (Modifier) -> Unit = { m -> Text(text, m, style = CrateType.secondary, color = c.textPrimary) }
    if (CrateTheme.isAccessibilitySize) {
        Column(Modifier.fillMaxWidth().padding(Space.m).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(label.uppercase(), style = CrateType.panelLabel, color = c.textSecondary)
            content(Modifier)
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(Space.m).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(label.uppercase(), style = CrateType.panelLabel, color = c.textSecondary, modifier = Modifier.widthIn(min = 64.dp).padding(top = 3.dp))
            content(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DataStep(
    token: String,
    onToken: (String) -> Unit,
    onSubmit: () -> Unit,
    check: TokenCheck,
    whatsThisOpen: Boolean,
    onWhatsThis: () -> Unit,
) {
    val c = CrateTheme.colors
    val context = LocalContext.current
    Text("Connect your data", style = titleStyle, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    Text("Your tags live in a private GitHub repo. Paste the access token for it.", style = CrateType.body, color = c.textSecondary)
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        PanelLabel("GitHub token")
        GitHubTokenField(token, onToken, onSubmit)
        TokenCheckLine(check)
    }
    Column(Modifier.fillMaxWidth().crateCard().padding(horizontal = Space.m, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Size.minTarget - Space.s)
                .semantics { stateDescription = if (whatsThisOpen) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClick = onWhatsThis),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("What's this?", style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp).graphicsLayer { rotationZ = if (whatsThisOpen) 180f else 0f }, tint = c.textSecondary)
        }
        AnimatedVisibility(whatsThisOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    buildAnnotatedString {
                        append("A fine-grained token with read and write access to the ")
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("crate-data") }
                        append(" repo only. Crate uses it to read tags and save your corrections. It's stored in encrypted storage on this phone.")
                    },
                    style = CrateType.secondary, color = c.textSecondary,
                )
                LibraryText.runbookURL("first-time-setup-m0")?.let { url ->
                    Box(Modifier.heightIn(min = Size.minTarget).clickable(role = Role.Button) { openURL(context, url) }, contentAlignment = Alignment.CenterStart) {
                        Text("How to create one (runbook)", style = CrateType.secondary.copy(textDecoration = TextDecoration.Underline), color = c.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsStep(
    topic: String,
    onTopic: (String) -> Unit,
    copied: Boolean,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = CrateTheme.colors
    Text("Get notified if Crate needs you", style = titleStyle, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    Text(
        "Crate runs by itself and stays quiet. If something breaks, it sends an alert through the free ntfy app. Subscribe to your topic there.",
        style = CrateType.body, color = c.textSecondary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        PanelLabel("Your topic")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).heightIn(min = Size.minTarget + Space.xs)
                    .background(c.fillControl, RoundedCornerShape(Radius.control)).padding(horizontal = Space.m),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    topic, onTopic, singleLine = true,
                    textStyle = CrateType.body.copy(fontFamily = FontFamily.Monospace, color = c.textPrimary),
                    cursorBrush = SolidColor(c.textPrimary),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "ntfy topic" },
                )
            }
            CardButton(if (copied) "Copied" else "Copy", onCopy, prominent = false)
        }
        Text("Use the same topic as NTFY_TOPIC in the repo's Actions secrets.", style = CrateType.footnote, color = c.textTertiary)
        OutlineButton(onClick = onOpen, enabled = topic.isNotBlank()) {
            Text("Open ntfy")
            Icon(Icons.Filled.NorthEast, null, Modifier.size(16.dp))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
        PanelLabel("You'll get")
        Column(
            Modifier.fillMaxWidth().crateCard().padding(Space.m).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text("ntfy · only if something breaks", style = CrateType.footnote.copy(fontSize = 12.sp), color = c.textSecondary)
            Text(
                "Spotify login rejected. See runbook: Rotate Spotify credentials",
                style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary,
            )
        }
    }
}

private fun randomTopic(): String {
    val chars = "abcdefghijkmnpqrstuvwxyz23456789"
    return "crate-" + (1..10).map { chars.random() }.joinToString("")
}

// region Shared token UI (onboarding step 2, Library → GitHub token)

sealed interface TokenCheck {
    data object Idle : TokenCheck
    data object Checking : TokenCheck
    data class Ok(val count: Int) : TokenCheck
    data class Failed(val message: String) : TokenCheck

    companion object {
        fun message(error: Throwable): String = when (error) {
            is GitHubError.Unauthorized -> "GitHub rejected this token. Check it has read and write access to the crate-data repo."
            is GitHubError.NotFound -> "The token works, but the crate-data repo has no library yet. Run the backfill first."
            else -> "Couldn't reach GitHub. Check your connection and try again."
        }
    }
}

/** Token field: masked after the first 14 characters unless focused, with a Paste button. */
@Composable
fun GitHubTokenField(token: String, onToken: (String) -> Unit, onSubmit: () -> Unit = {}) {
    val c = CrateTheme.colors
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val masked = if (token.length <= 14) token else token.take(14) + "•".repeat(minOf(12, token.length - 14))
    val mono = CrateType.secondary.copy(fontFamily = FontFamily.Monospace, color = c.textPrimary)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).heightIn(min = Size.minTarget + Space.xs)
                .background(c.fillControl, RoundedCornerShape(Radius.control)).padding(horizontal = Space.m),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (token.isEmpty()) Text("github_pat_…", style = mono, color = c.textTertiary)
            BasicTextField(
                token, { onToken(it.trim()) }, singleLine = true,
                textStyle = mono.copy(color = if (focused || token.isEmpty()) c.textPrimary else androidx.compose.ui.graphics.Color.Transparent),
                cursorBrush = SolidColor(c.textPrimary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = "GitHub token" },
            )
            if (!focused && token.isNotEmpty()) {
                Text(masked, style = mono, maxLines = 1, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        Box(
            Modifier.heightIn(min = Size.minTarget + Space.xs)
                .background(c.fillControlActive, RoundedCornerShape(Radius.control))
                .clickable(role = Role.Button) {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    val s = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!s.isNullOrBlank()) { onToken(s.trim()); onSubmit() }
                }
                .padding(horizontal = Space.m),
            contentAlignment = Alignment.Center,
        ) {
            Text("Paste", style = CrateType.secondary.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
        }
    }
}

/** "✓ 1,015 tracks found · 982 tagged", or the error. */
@Composable
fun TokenCheckLine(state: TokenCheck) {
    val model = rememberModel()
    val c = CrateTheme.colors
    when (state) {
        TokenCheck.Idle -> Unit
        TokenCheck.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = c.textSecondary, strokeWidth = 2.dp)
            Text("Checking…", style = CrateType.secondary, color = c.textSecondary)
        }
        is TokenCheck.Ok -> CheckLine(Icons.Filled.CheckCircle, c.success, c.successFill, buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${LibraryText.number(state.count)} tracks found") }
            append(" · ${LibraryText.number(model.index.taggedCount)} tagged")
        })
        is TokenCheck.Failed -> CheckLine(Icons.Filled.Warning, c.error, c.errorFill, buildAnnotatedString { append(state.message) })
    }
}

@Composable
private fun CheckLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, fill: androidx.compose.ui.graphics.Color, text: androidx.compose.ui.text.AnnotatedString) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.minTarget)
            .background(fill, RoundedCornerShape(Radius.control))
            .padding(horizontal = Space.m, vertical = Space.s)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Text(text, style = CrateType.secondary, color = CrateTheme.colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

// endregion
