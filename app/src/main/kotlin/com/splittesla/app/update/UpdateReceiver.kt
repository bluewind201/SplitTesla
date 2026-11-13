package com.splittesla.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.splittesla.app.MainActivity

/** PackageInstaller's answer to [AppUpdater.install]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // First self-update (or Android 11 and older): Android shows its own "update this app?" screen.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    runCatching { context.startActivity(it) }
                }
                AppUpdater.set(UpdatePhase.INSTALLING, "Android 설치 확인 화면에서 '업데이트'를 누르세요")
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.set(UpdatePhase.UP_TO_DATE, "설치 완료")
            else -> {
                UpdatePrefs(context).relaunch = false
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val reason = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "취소됨" else "실패 ($status) ${msg.orEmpty()}"
                AppUpdater.set(UpdatePhase.READY, "설치 $reason")
            }
        }
    }
}

/** Runs in the new version right after an update: reopen the dashboard if this app started the install. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = UpdatePrefs(context)
        if (!prefs.relaunch) return
        prefs.relaunch = false
        // Allowed from the background because the app holds "display over other apps".
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
