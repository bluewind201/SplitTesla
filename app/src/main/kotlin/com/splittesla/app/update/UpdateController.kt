package com.splittesla.app.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Checks GitHub `latest.json` at start and every 6 hours, and opens the
 * release APK download when requested.
 */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
    @Suppress("UNUSED_PARAMETER") private val safeToInstall: () -> Boolean = { true },
) {
    private val prefs = UpdatePrefs(context)
    private var loop: Job? = null

    var auto: Boolean
        get() = prefs.auto
        set(value) {
            prefs.auto = value
            if (value) start() else loop?.cancel()
        }

    init {
        AppUpdater.init(context)
        start()
    }

    private fun start() {
        if (!prefs.auto || loop?.isActive == true) return
        loop = scope.launch {
            delay(START_DELAY_MS)
            while (true) {
                AppUpdater.check(context)
                delay(INTERVAL_MS)
            }
        }
    }

    fun checkNow(openDownloadIfNew: Boolean) {
        scope.launch {
            val info = AppUpdater.check(context)
            if (openDownloadIfNew && info != null) {
                AppUpdater.openDownload(context, info)
            }
        }
    }

    fun onForeground() = Unit

    private companion object {
        const val START_DELAY_MS = 20_000L
        const val INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
