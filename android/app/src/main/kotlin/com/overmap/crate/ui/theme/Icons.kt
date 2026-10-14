package com.overmap.crate.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RemoveFromQueue
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The closest Material icon (icons-extended) for an SF Symbol name, as stored in vibes.json / presets.json and used
 * by the iOS app. Unknown names drop a trailing `.fill` / `.circle` and retry, then fall back to the tag glyph
 * (the same fallback as the iOS VibeChip).
 */
fun crateIcon(sfSymbol: String): ImageVector {
    SF_SYMBOLS[sfSymbol]?.let { return it }
    var name = sfSymbol
    while (name.contains('.')) {
        name = name.substringBeforeLast('.')
        SF_SYMBOLS[name]?.let { return it }
    }
    return FALLBACK_ICON
}

/** Whether [crateIcon] has a mapping for this exact SF Symbol name. */
fun hasCrateIcon(sfSymbol: String): Boolean = sfSymbol in SF_SYMBOLS

/** Every mapped SF Symbol name (for the gallery and tests). */
val crateIconNames: Set<String> get() = SF_SYMBOLS.keys

private val FALLBACK_ICON: ImageVector get() = Icons.Filled.Tag

private val SF_SYMBOLS: Map<String, ImageVector> by lazy {
    mapOf(
        // Vibes: VibeSymbols.defaults and VibeSymbols.picker.
        "circle.dotted.circle" to Icons.Filled.Adjust,
        "moon.fill" to Icons.Filled.DarkMode,
        "headphones" to Icons.Filled.Headphones,
        "dumbbell.fill" to Icons.Filled.FitnessCenter,
        "bed.double.fill" to Icons.Filled.Hotel,
        "airplane" to Icons.Filled.Flight,
        "beach.umbrella.fill" to Icons.Filled.BeachAccess,
        "tram.fill" to Icons.Filled.Tram,
        "moon" to Icons.Filled.DarkMode,
        "bolt.fill" to Icons.Filled.Bolt,
        "bolt" to Icons.Filled.Bolt,
        "sparkles" to Icons.Filled.AutoAwesome,
        "sunrise.fill" to Icons.Filled.WbTwilight,
        "leaf.fill" to Icons.Filled.Eco,
        "party.popper.fill" to Icons.Filled.Celebration,
        "flame.fill" to Icons.Filled.LocalFireDepartment,
        "cloud.rain.fill" to Icons.Filled.Umbrella,
        "cloud.fog.fill" to Icons.Filled.Cloud,
        "cloud.fill" to Icons.Filled.Cloud,
        "face.smiling" to Icons.Outlined.EmojiEmotions,
        "figure.mind.and.body" to Icons.Filled.SelfImprovement,
        "camera.macro" to Icons.Filled.LocalFlorist,
        "car.fill" to Icons.Filled.DirectionsCar,
        "sun.max.fill" to Icons.Filled.WbSunny,
        "snowflake" to Icons.Filled.AcUnit,
        "water.waves" to Icons.Filled.Waves,
        "figure.run" to Icons.AutoMirrored.Filled.DirectionsRun,
        "cup.and.saucer.fill" to Icons.Filled.Coffee,
        "building.2.fill" to Icons.Filled.Apartment,
        "tag" to Icons.Filled.Tag,
        "tag.fill" to Icons.Filled.Tag,
        "number" to Icons.Filled.Tag,
        // Presets (data/presets.json) and preset editor.
        "sun.horizon.fill" to Icons.Filled.WbTwilight,
        "sun.horizon" to Icons.Outlined.WbTwilight,
        "music.note" to Icons.Filled.MusicNote,
        "music.note.list" to Icons.AutoMirrored.Filled.QueueMusic,
        "music.quarternote.3" to Icons.Filled.LibraryMusic,
        "star.fill" to Icons.Filled.Star,
        "heart.fill" to Icons.Filled.Favorite,
        "heart" to Icons.Outlined.FavoriteBorder,
        "heart.slash" to Icons.Filled.HeartBroken,
        "bookmark" to Icons.Outlined.BookmarkBorder,
        "bookmark.fill" to Icons.Filled.Bookmark,
        // App chrome (ios/ systemName: / systemImage:).
        "arrow.up.forward.app" to Icons.AutoMirrored.Filled.OpenInNew,
        "arrow.up.right" to Icons.Filled.NorthEast,
        "arrow.up.to.line" to Icons.Filled.VerticalAlignTop,
        "arrow.down.circle" to Icons.Filled.ArrowCircleDown,
        "arrow.counterclockwise" to Icons.Filled.Refresh,
        "arrow.clockwise" to Icons.Filled.Refresh,
        "arrow.up.and.down" to Icons.Filled.SwapVert,
        "bell" to Icons.Outlined.Notifications,
        "bell.badge" to Icons.Filled.NotificationsActive,
        "checkmark" to Icons.Filled.Check,
        "checkmark.circle.fill" to Icons.Filled.CheckCircle,
        "chevron.down" to Icons.Filled.KeyboardArrowDown,
        "chevron.up" to Icons.Filled.KeyboardArrowUp,
        "chevron.left" to Icons.AutoMirrored.Filled.KeyboardArrowLeft,
        "chevron.right" to Icons.AutoMirrored.Filled.KeyboardArrowRight,
        "circle" to Icons.Outlined.Circle,
        "circle.dotted" to Icons.Outlined.TripOrigin,
        "circle.lefthalf.filled" to Icons.Filled.Contrast,
        "exclamationmark" to Icons.Filled.PriorityHigh,
        "exclamationmark.triangle" to Icons.Outlined.Warning,
        "exclamationmark.triangle.fill" to Icons.Filled.Warning,
        "clock.badge.exclamationmark" to Icons.Filled.Schedule,
        "line.3.horizontal" to Icons.Filled.DragHandle,
        "lock.fill" to Icons.Filled.Lock,
        "magnifyingglass" to Icons.Filled.Search,
        "minus" to Icons.Filled.Remove,
        "minus.circle" to Icons.Outlined.RemoveCircleOutline,
        "nosign" to Icons.Filled.Block,
        "paperplane" to Icons.AutoMirrored.Outlined.Send,
        "paperplane.fill" to Icons.AutoMirrored.Filled.Send,
        "pencil" to Icons.Filled.Edit,
        "play.fill" to Icons.Filled.PlayArrow,
        "plus" to Icons.Filled.Add,
        "plus.circle.fill" to Icons.Filled.AddCircle,
        "rectangle.badge.plus" to Icons.Filled.AddToQueue,
        "rectangle.badge.minus" to Icons.Filled.RemoveFromQueue,
        "slider.horizontal.3" to Icons.Filled.Tune,
        "square.stack.3d.down.forward.fill" to Icons.Filled.Layers,
        "square.stack" to Icons.Outlined.Layers,
        "trash" to Icons.Outlined.Delete,
        "trash.fill" to Icons.Filled.Delete,
        "xmark" to Icons.Filled.Close,
        "xmark.circle.fill" to Icons.Filled.Cancel,
        "xmark.octagon.fill" to Icons.Filled.Report,
        "doc.on.doc" to Icons.Filled.ContentCopy,
        "questionmark.circle" to Icons.AutoMirrored.Outlined.HelpOutline,
        "waveform" to Icons.Filled.GraphicEq,
        "waveform.badge.magnifyingglass" to Icons.Filled.GraphicEq,
        "gearshape" to Icons.Filled.Settings,
        "gear" to Icons.Filled.Settings,
        "ellipsis" to Icons.Filled.MoreHoriz,
        "square.and.arrow.up" to Icons.Filled.Share,
        "info.circle" to Icons.Outlined.Info,
        "person" to Icons.Filled.Person,
        "house" to Icons.Filled.Home,
    )
}
