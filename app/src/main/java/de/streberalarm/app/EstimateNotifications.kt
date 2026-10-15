package de.streberalarm.app

import android.app.NotificationManager
import android.content.Context
import de.streberalarm.core.SchoolData
import java.time.ZonedDateTime

/** Compatibility boundary for old intents and repository callers. Estimates remain inactive. */
internal object EstimateNotifications {
    const val CHANNEL = "estimates"
    const val EXTRA_EXAM = "estimateExam"
    fun allowed(context: Context) = false
    fun retire(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("estimate:") == true || it.notification.channelId == CHANNEL }
            .forEach { manager.cancel(it.tag, it.id) }
        manager.deleteNotificationChannel(CHANNEL)
    }
    fun postDue(context: Context, data: SchoolData, now: ZonedDateTime): Set<String> {
        retire(context)
        return emptySet()
    }
}
