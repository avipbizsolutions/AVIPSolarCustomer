package com.avip.solarcustomer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Website (GitHub) build: compulsory update from the app's GitHub Releases.
 * The newer APK is downloaded and installed from the start screen.
 */
class UpdateGate(private val a: SplashActivity) {

    private var pendingRelease: ReleaseInfo? = null

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        try {
                            a.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
                        } catch (_: Exception) {
                        }
                    }
                    block("Installer नहीं खुल सका।")
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> block("Update install नहीं हुआ।")
            }
        }
    }

    private val unknownSourcesLauncher =
        a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val r = pendingRelease
            if (r != null && Updater.canInstall(a)) a.lifecycleScope.launch { downloadAndInstall(r) }
            else if (r != null) block("AVIP Solar app को updates install करने की अनुमति दें, फिर Update now दबाएँ।")
            else a.goNext()
        }

    fun onCreate() {
        ContextCompat.registerReceiver(
            a, installStatusReceiver, IntentFilter(Updater.ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun onResume() {}

    fun onDestroy() {
        try { a.unregisterReceiver(installStatusReceiver) } catch (_: Exception) {}
    }

    /** True when a newer version exists; the gate then shows the update on the start screen. */
    suspend fun updateNeeded(): Boolean {
        val latest = withTimeoutOrNull(5000L) { withContext(Dispatchers.IO) { Updater.fetchLatest() } } ?: return false
        if (latest.versionCode <= AppInfo.currentVersionCode(a)) return false
        pendingRelease = latest
        if (Updater.canInstall(a)) downloadAndInstall(latest) else askInstallPermission(latest)
        return true
    }

    private suspend fun downloadAndInstall(release: ReleaseInfo) {
        a.status.text = "Version ${release.versionName} download हो रहा है…"
        a.progress.isIndeterminate = false
        a.progress.progress = 0
        val apk = withContext(Dispatchers.IO) {
            Updater.download(a, release) { pct -> a.runOnUiThread { a.progress.progress = pct } }
        }
        if (apk == null) { block("Download नहीं हुआ। Internet check करके फिर कोशिश करें।"); return }
        a.status.text = "Update install हो रहा है…"
        try {
            withContext(Dispatchers.IO) { Updater.install(a, apk) }
        } catch (_: Exception) {
            block("Update install नहीं हो सका।")
        }
    }

    private fun askInstallPermission(release: ReleaseInfo) {
        AlertDialog.Builder(a)
            .setTitle("नया Version ज़रूरी है")
            .setMessage("Version ${release.versionName} के बिना App नहीं खुलेगा। AVIP Solar app को अपने updates install करने की अनुमति दें? यह सिर्फ एक बार पूछा जाएगा।")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                unknownSourcesLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
                )
            }
            .setNegativeButton("अभी नहीं") { _, _ -> block("App चलाने के लिए यह update ज़रूरी है।") }
            .show()
    }

    private fun block(reason: String) {
        val r = pendingRelease ?: run { a.goNext(); return }
        a.blockForUpdate(
            "Update ज़रूरी है — Version ${r.versionName}\n$reason",
            onUpdate = {
                if (Updater.canInstall(a)) a.lifecycleScope.launch { downloadAndInstall(r) } else askInstallPermission(r)
            },
            linkText = "Website से Download करें",
            onLink = {
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avipbizsolutions/AVIPSolarCustomer/releases/latest/download/AVIP-Solar.apk")))
                } catch (_: Exception) {}
            },
        )
    }
}
