package com.overmap.crate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.screens.RootView
import com.overmap.crate.ui.theme.CrateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DebugLaunch.read(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (DebugLaunch.isDemo && !crateModel.isDemo) crateModel.useDemoData()
        setContent { CrateTheme { RootView() } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DebugLaunch.read(intent)
        handle(intent)
    }

    /** `crate://spotify-callback?…` finishes a login; any other `crate://` link goes to the model. */
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "crate") return
        if (crateModel.spotify.handleCallback(uri)) return
        crateModel.open(uri, trusted = intent.getStringExtra(LINK_KEY_EXTRA) == crateModel.linkKey)
    }

    companion object {
        /** Extra on Crate's own launcher-shortcut intents (see AppModel.linkKey). */
        const val LINK_KEY_EXTRA = "com.overmap.crate.link-key"
    }
}
