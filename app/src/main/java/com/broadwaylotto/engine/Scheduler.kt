package com.broadwaylotto.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Two layers so we hit opening times without hammering the battery:
 *  1. An exact alarm set for the next time a matching lottery opens.
 *  2. A 15-minute periodic worker as a safety net (picks up newly announced lotteries, missed alarms).
 */
object Scheduler {
    private const val PERIODIC = "periodic-check"
    private const val ONCE = "check-now"

    fun ensurePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EntryWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EntryWorker>().build(),
        )
    }

    fun setAlarm(context: Context, at: Instant?) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            am.cancel(pi)
            return
        }
        val millis = at.toEpochMilli()
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }
}

class EntryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val next = EntryEngine(applicationContext).run()
        Scheduler.setAlarm(applicationContext, next)
        return Result.success()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.checkNow(context)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.ensurePeriodic(context)
        Scheduler.checkNow(context)
    }
}
