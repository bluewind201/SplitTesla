package com.splittesla.app.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Checks GitHub at start and every 6 hours (as Portunus does), downloads a
 * newer build in the background, and installs it only while this app is on
 * screen and the car is not moving, since an install restarts the app and
 * drops the car links.
 */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** On screen and parked: an install may restart the app now. */
    private val safeToInstall: () -> Boolean,
) {
    private val prefs = UpdatePrefs(context)
    private val mutex = Mutex()
    private var ready: File? = null
    private var loop: Job? = null

    var auto: Boolean
        get() = prefs.auto
        set(value) {
            prefs.auto = value
            if (value) start() else loop?.cancel()
        }

    init {
        AppUpdater.init(context)
        prefs.relaunch = false
        start()
    }

    private fun start() {
        if (!prefs.auto || loop?.isActive == true) return
        loop = scope.launch {
            delay(START_DELAY_MS) // let BLE and permissions settle first
            while (true) {
                runOnce(install = true)
                delay(INTERVAL_MS)
            }
        }
    }

    /** "지금 확인" in settings: check and download, and install if allowed. */
    fun checkNow(install: Boolean) {
        scope.launch { runOnce(install) }
    }

    /** The app came back on screen: a waiting download can go in now. */
    fun onForeground() {
        if (prefs.auto && ready != null) scope.launch { installIfSafe() }
    }

    private suspend fun runOnce(install: Boolean) = mutex.withLock {
        val info = AppUpdater.check(context) ?: return@withLock
        ready = AppUpdater.download(context, info) ?: return@withLock
        if (install) installIfSafeLocked()
    }

    private suspend fun installIfSafe() = mutex.withLock { installIfSafeLocked() }

    private fun installIfSafeLocked() {
        val file = ready ?: return
        if (!file.exists()) {
            ready = null
            return
        }
        if (!AppUpdater.canInstall(context)) {
            AppUpdater.set(UpdatePhase.READY, "설정 → 자동 업데이트에서 '앱 설치 허용'을 켜 주세요")
            return
        }
        if (!safeToInstall()) {
            AppUpdater.set(UpdatePhase.READY, "정차 중에 앱 화면에서 설치됩니다")
            return
        }
        AppUpdater.install(context, file)
    }

    private companion object {
        const val START_DELAY_MS = 20_000L
        const val INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
