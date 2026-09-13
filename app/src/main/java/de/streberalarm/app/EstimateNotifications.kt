package de.streberalarm.app

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.streberalarm.core.*
import java.time.ZonedDateTime

internal object EstimateNotifications {
    const val CHANNEL = "estimates"
    const val EXTRA_EXAM = "estimateExam"
    private const val PREFIX = "estimate:"
    private const val EXTRA_KEY = "estimateKey"

    fun allowed(context: Context): Boolean =
        ReminderScheduler.allowed(context) &&
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL)
                ?.importance != NotificationManager.IMPORTANCE_NONE

    // Called under the repository mutex: simultaneous resume, save and alarm delivery cannot
    // duplicate prompts.
    fun postDue(context: Context, data: SchoolData, now: ZonedDateTime): Set<String> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val portable = SchoolZonedTime.fromEpochMillis(now.toInstant().toEpochMilli(), now.zone.id)
        val due = EstimatePrompts.due(data, portable)
        val validKeys = due.map { EstimatePrompts.key(it) }.toSet()
        manager.activeNotifications
            .filter { it.tag?.startsWith(PREFIX) == true }
            .forEach { posted ->
                if (posted.notification.extras.getString(EXTRA_KEY) !in validKeys)
                    manager.cancel(posted.tag, posted.id)
            }
        if (!allowed(context)) return emptySet()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Wie lief dein Test?",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val delivered = mutableSetOf<String>()
        for (exam in due.filter { EstimatePrompts.key(it) !in data.delivered }) {
            val open =
                Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("streberalarm://estimate/${exam.id}"))
                    .putExtra(EXTRA_EXAM, exam.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending =
                PendingIntent.getActivity(
                    context,
                    0,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val title =
                if (exam.stage == Stage.GRADED) "Welche Note hattest du erwartet?"
                else "Wie lief dein Test?"
            val notification =
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_streberalarm_notification)
                    .setContentTitle(title)
                    .setContentText("${exam.title} · Tippe für deine gefühlte Note.")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .addExtras(
                        android.os.Bundle().apply {
                            putString(EXTRA_KEY, EstimatePrompts.key(exam))
                        }
                    )
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(PREFIX + exam.id, 1, notification)
                delivered += EstimatePrompts.key(exam)
            } catch (_: SecurityException) {
                /* The in-app question remains available. */
            }
        }
        return delivered
    }
}
