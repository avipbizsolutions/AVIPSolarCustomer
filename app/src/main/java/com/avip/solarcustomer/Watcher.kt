package com.avip.solarcustomer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Every few hours (when there is internet) asks the server for the latest
 * progress and shows a notification when something moved forward: a stage
 * done, a ticket visit fixed, a referral installed, a reward paid.
 */
class Watcher(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        if (!Session.loggedIn(c)) return@withContext Result.success()
        try {
            Api.refreshConfig(c)
            val (_, changes) = Store.refresh(c)
            if (changes.isNotEmpty()) notify(c, changes)
            Result.success()
        } catch (e: ApiException) {
            if (e.sessionExpired) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val CHANNEL = "progress"
        private const val WORK = "progress-check"

        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<Watcher>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(c: Context) {
            WorkManager.getInstance(c).cancelUniqueWork(WORK)
        }

        fun notify(c: Context, changes: List<String>) {
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                c, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
            val manager = c.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Solar progress updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                c, 0,
                Intent(c, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            val style = NotificationCompat.InboxStyle()
            changes.take(6).forEach { style.addLine(it) }
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_update)
                .setContentTitle("AVIP Solar: नया update")
                .setContentText(changes.first())
                .setStyle(if (changes.size > 1) style else NotificationCompat.BigTextStyle().bigText(changes.first()))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(c).notify(2001, n)
            } catch (_: SecurityException) {
            }
        }
    }
}
