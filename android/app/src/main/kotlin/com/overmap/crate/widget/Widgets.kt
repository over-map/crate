package com.overmap.crate.widget

import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.overmap.crate.BuildConfig
import com.overmap.crate.app.SharedPublisher
import com.overmap.crate.core.SendStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Refreshes every Crate home-screen widget after the shared preset file or the send status changed. */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    /** Safe from any thread; returns at once. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { update(app) }
    }

    /** Redraws both widgets, then schedules the return to idle when a Sent / Sending frame is showing. */
    suspend fun update(context: Context) {
        lock.withLock {
            val manager = GlanceAppWidgetManager(context)
            for (widget in listOf(PresetWidget(), GridWidget())) runCatching {
                // Bump the per-widget revision so running compositions re-read presets.json / status.json.
                for (id in manager.getGlanceIds(widget.javaClass)) {
                    updateAppWidgetState(context, id) { it[PresetWidget.revisionKey] = (it[PresetWidget.revisionKey] ?: 0) + 1 }
                }
                widget.updateAll(context)
            }
        }
        scheduleIdle(context)
        publishPreviews(context)
    }

    /**
     * Sent holds [SendStatus.sentHold] seconds and Sending gives up after [SendStatus.sendingTimeout]; widgets can't
     * time themselves, so a one-off worker redraws them when that frame ends (Swift: the timeline's idle entry).
     */
    private fun scheduleIdle(context: Context) {
        val status = SharedPublisher.store(context).loadStatus() ?: return
        val now = Instant.now()
        if (status.visible(now) == null || status.phase == SendStatus.Phase.failed) return
        val hold = if (status.phase == SendStatus.Phase.sent) SendStatus.sentHold else SendStatus.sendingTimeout
        val end = status.at.plusMillis((hold * 1000).toLong())
        val delay = Duration.between(now, end).toMillis().coerceAtLeast(0) + 500
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IDLE_WORK, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetIdleWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
            )
        }
    }

    /** Android 15+: generated widget-picker previews from the sample presets, once per app version. */
    private suspend fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences("crate.widget", Context.MODE_PRIVATE)
        val version = "${BuildConfig.VERSION_CODE}-$PREVIEW_REVISION"
        if (prefs.getString("previews", null) == version) return
        val manager = GlanceAppWidgetManager(context)
        val ok = runCatching {
            listOf(PresetWidgetReceiver::class, GridWidgetReceiver::class).all {
                manager.setWidgetPreviews(it) == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
            }
        }.getOrDefault(false)
        if (ok) prefs.edit().putString("previews", version).apply()
    }

    /** Bump when the preview content changes so installed builds regenerate it. */
    private const val PREVIEW_REVISION = 1
    private const val IDLE_WORK = "crate-widget-idle"
}

/** Redraws the widgets when a Sent / Sending frame has run out. */
class WidgetIdleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Widgets.update(applicationContext)
        return Result.success()
    }
}
