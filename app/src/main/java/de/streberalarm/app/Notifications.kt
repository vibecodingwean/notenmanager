package de.streberalarm.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.streberalarm.core.*
import java.time.*
import kotlinx.coroutines.launch

object ReminderScheduler {
    const val CHANNEL = "learning"

    fun allowed(c: Context) =
        NotificationManagerCompat.from(c).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)

    fun exact(c: Context) =
        Build.VERSION.SDK_INT < 31 ||
            c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(c: Context, data: SchoolData, now: ZonedDateTime = ZonedDateTime.now()) {
        val alarms = c.getSystemService(AlarmManager::class.java)
        val pending =
            PendingIntent.getBroadcast(
                c,
                1,
                Intent(c, ReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        alarms.cancel(pending)
        val portable = SchoolZonedTime.fromEpochMillis(now.toInstant().toEpochMilli(), now.zone.id)
        val at =
            listOfNotNull(
                    Reminders.plan(data, now).firstOrNull()?.at?.toInstant()?.toEpochMilli(),
                    EstimatePrompts.nextAt(data, portable, EstimateNotifications.allowed(c))
                        ?.epochMillis,
                )
                .minOrNull() ?: return
        try {
            if (exact(c)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                        CHANNEL,
                        "Lernerinnerungen",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply { description = "Gebündelte Lernhinweise um 16 Uhr" }
            )
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as StreberAlarmApp
        app.scope.launch {
            try {
                val now = ZonedDateTime.now()
                app.repository.refreshEstimates(now)
                val data = app.repository.current()
                val today =
                    Reminders.plan(data, now.toLocalDate().atStartOfDay(now.zone)).find {
                        it.at.toLocalDate() == now.toLocalDate() && it.at <= now
                    }
                if (today != null && ReminderScheduler.allowed(context)) {
                    ReminderScheduler.channel(context)
                    val lines =
                        today.notices.map { n ->
                            val minutes =
                                data.studies
                                    .filter { it.assessmentId == n.examId }
                                    .sumOf { it.seconds(System.currentTimeMillis()) } / 60
                            n.text + if (minutes > 0) " · $minutes Min. gelernt" else ""
                        }
                    val open =
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    val notification =
                        NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
                            .setSmallIcon(
                                de.streberalarm.app.R.drawable.ic_streberalarm_notification
                            )
                            .setContentTitle("Dein Lernplan für heute")
                            .setContentText(lines.first())
                            .setStyle(
                                NotificationCompat.InboxStyle().also { style ->
                                    lines.forEach { style.addLine(it) }
                                }
                            )
                            .setContentIntent(open)
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                            .build()
                    try {
                        NotificationManagerCompat.from(context)
                            .notify(now.toLocalDate().toEpochDay().toInt(), notification)
                        app.repository.update {
                            it.copy(delivered = it.delivered + today.notices.map { n -> n.key })
                        }
                    } catch (_: SecurityException) {
                        ReminderScheduler.schedule(context, data, now)
                    }
                } else ReminderScheduler.schedule(context, data, now)
            } finally {
                pending.finish()
            }
        }
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                )
        )
            return
        val pending = goAsync()
        val app = context.applicationContext as StreberAlarmApp
        app.scope.launch {
            try {
                app.repository.refreshEstimates()
            } finally {
                pending.finish()
            }
        }
    }
}
