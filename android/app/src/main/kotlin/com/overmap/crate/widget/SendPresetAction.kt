package com.overmap.crate.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import com.overmap.crate.MainActivity
import com.overmap.crate.app.SecureStore
import com.overmap.crate.app.SecureTokenStore
import com.overmap.crate.app.SharedPublisher
import com.overmap.crate.core.CrateIdentity
import com.overmap.crate.core.CrateLink
import com.overmap.crate.core.PresetSender
import com.overmap.crate.core.SendOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A widget tap (Swift: `SendPresetToNow`): fills Now with the preset's tracks without opening Crate. The sender
 * records Sending, then Sent or the failure, in `status.json`; every widget redraws on each change.
 */
class SendPresetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[presetKey] ?: return
        send(context.applicationContext, id)
    }

    companion object {
        val presetKey = ActionParameters.Key<String>("preset")

        suspend fun send(context: Context, presetID: String): SendOutcome = withContext(Dispatchers.IO) {
            val spotify = CrateIdentity.spotifyClient(tokens = SecureTokenStore(SecureStore(context)))
            PresetSender(SharedPublisher.store(context), spotify).send(presetID, onStatus = { Widgets.refresh(context) })
        }
    }
}

/** Runs [SendPresetAction] for one preset. */
internal fun sendAction(presetID: String): Action =
    actionRunCallback<SendPresetAction>(actionParametersOf(SendPresetAction.presetKey to presetID))

/** Opens a `crate://` link in MainActivity (`crate://now` → Now in Spotify, `crate://open` → the app). */
internal fun linkAction(context: Context, link: String): Action =
    actionStartActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

internal fun openNowAction(context: Context) = linkAction(context, CrateLink.now)
internal fun openAppAction(context: Context) = linkAction(context, CrateLink.open)
