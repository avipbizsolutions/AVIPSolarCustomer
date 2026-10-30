package com.avip.solarcustomer

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** Installed version, used by both the website and the Play Store build. */
object AppInfo {
    const val PRIVACY_URL = "https://avipbizsolutions.github.io/AVIPSolarCustomer/privacy-policy.html"

    fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}
