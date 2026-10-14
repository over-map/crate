package com.overmap.crate.app

import android.content.Intent
import com.overmap.crate.BuildConfig

/**
 * Debug launch extras for checking screens on the emulator without logging in (the Android twin of iOS's
 * `-demo -tab -screen -gallery`):
 * `adb shell am start -n com.overmap.crate/.MainActivity --ez demo true --es tab build|daily|library --es screen <name>`
 * and `--ez gallery true`. Ignored in release builds.
 */
object DebugLaunch {
    var isDemo = false
        private set
    var tab: String? = null
        private set
    var screen: String? = null
        private set
    var gallery = false
        private set
    /** Long screens open scrolled to the end (screenshots of lower sections). */
    var bottom = false
        private set

    fun read(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        isDemo = isDemo || intent.getBooleanExtra("demo", false)
        tab = intent.getStringExtra("tab") ?: tab
        screen = intent.getStringExtra("screen") ?: screen
        gallery = gallery || intent.getBooleanExtra("gallery", false)
        bottom = bottom || intent.getBooleanExtra("bottom", false)
    }
}
