package com.broadwaylotto.engine

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.broadwaylotto.model.EntryRecord
import com.broadwaylotto.model.EntryStatus
import com.broadwaylotto.model.Site
import com.broadwaylotto.ui.MainActivity
import com.broadwaylotto.ui.opensInApp

object Notifier {
    private const val CHANNEL = "entries"

    fun entry(context: Context, rec: EntryRecord) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lottery entries", NotificationManager.IMPORTANCE_HIGH))

        val title = when (rec.status) {
            EntryStatus.ENTERED -> "Entered: ${rec.showTitle}"
            EntryStatus.NEEDS_MANUAL -> "Action needed: ${rec.showTitle}"
            EntryStatus.FAILED -> "Entry failed: ${rec.showTitle}"
        }
        // Lucky Seat links open in the app's own signed-in browser; everything else in the user's browser.
        val target = if (opensInApp(rec.deepLink)) {
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_URL, rec.deepLink)
                .putExtra(MainActivity.EXTRA_PRESELECT, rec.preselect.toTypedArray())
                .putExtra(MainActivity.EXTRA_TICKETS, rec.tickets)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(rec.deepLink))
        }
        val open = PendingIntent.getActivity(
            context, rec.lotteryId.hashCode(), target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$title (${rec.site.label})")
            .setContentText(rec.message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(rec.lotteryId.hashCode(), n)
    }

    /** "A lottery is open" for an app-only site (TodayTix, Hamilton): tapping it enters that show. */
    fun appLotteryOpen(context: Context, site: Site, title: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lottery entries", NotificationManager.IMPORTANCE_HIGH))
        val id = ("${site.name}:$title").hashCode()
        val enter = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_APP_ENTER, "${site.name}|$title")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("${site.label} lottery open: $title")
                .setContentText("Tap to enter. Keep your phone unlocked.")
                .setContentIntent(enter)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A plain notification that opens the app. */
    fun simple(context: Context, id: Int, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lottery entries", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, id, context.packageManager.getLaunchIntentForPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
