package com.avip.solarcustomer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Play Store build: compulsory update through Google Play's in-app update
 * (full-screen "immediate" update). Google Play downloads and installs it;
 * the app never installs anything itself.
 */
class UpdateGate(private val a: SplashActivity) {

    private val manager = AppUpdateManagerFactory.create(a)
    private var active = false

    private val launcher = a.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // RESULT_OK: Google Play installs the update and restarts the app
        if (result.resultCode != Activity.RESULT_OK) block()
    }

    fun onCreate() {}

    fun onDestroy() {}

    /** An update that was started and left half-way is resumed when the user comes back. */
    fun onResume() {
        if (!active) return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) startUpdate(info)
        }
    }

    /** True when Google Play has a newer version; the update screen then takes over. */
    suspend fun updateNeeded(): Boolean {
        val info = withTimeoutOrNull(5000L) { fetch() } ?: return false
        val needed = when (info.updateAvailability()) {
            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> true
            UpdateAvailability.UPDATE_AVAILABLE -> info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            else -> false
        }
        if (!needed) return false
        active = true
        a.status.text = "नया version आया है…"
        startUpdate(info)
        return true
    }

    private suspend fun fetch(): AppUpdateInfo? = suspendCancellableCoroutine { cont ->
        manager.appUpdateInfo
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resume(null) } // not from Play / offline: open normally
    }

    private fun startUpdate(info: AppUpdateInfo) {
        try {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build())
        } catch (_: Exception) {
            block()
        }
    }

    private fun block() {
        a.blockForUpdate(
            "Update ज़रूरी है\nApp चलाने के लिए Play Store से नया version install करें।",
            onUpdate = {
                manager.appUpdateInfo
                    .addOnSuccessListener { startUpdate(it) }
                    .addOnFailureListener { openStore() }
            },
            linkText = "Play Store खोलें",
            onLink = { openStore() },
        )
    }

    private fun openStore() {
        val id = a.packageName
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")))
        } catch (_: Exception) {
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))) } catch (_: Exception) {}
        }
    }
}
