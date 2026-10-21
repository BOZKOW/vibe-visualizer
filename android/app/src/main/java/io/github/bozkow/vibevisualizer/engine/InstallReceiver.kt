package io.github.bozkow.vibevisualizer.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Android's installer reports here: it needs your OK (the first time), it's done, or it failed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val c = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                Updates.confirm = c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updates.showConfirm()   // now if the app is on screen, otherwise as soon as it is
            }
            PackageInstaller.STATUS_SUCCESS -> Updates.stage = ""   // the new version starts the next time the app opens
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updates.stage = ""
            else -> { Updates.stage = ""; E.toast("The update didn’t install: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"), "warn") }
        }
    }
}
