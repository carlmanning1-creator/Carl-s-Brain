package com.carlmanning.carlsbrain.data.local.worker

/**
 * The four smart-notification slots.
 *
 * This file used to hold a `SmartNotificationWorker` as well — a `CoroutineWorker` that built
 * and posted the notification. It was dead: the only thing that ever enqueued it was
 * `NotificationScheduler.scheduleSlot`, itself dead since the slots moved from WorkManager to
 * AlarmManager (they have to land on a minute, and WorkManager does not promise that). The
 * worker sat beside the live `SmartNotificationReceiver` with a near-identical name, on a
 * *different* mechanism, carrying a second copy of the notification-building block — the exact
 * shape that lets two implementations drift until nobody knows which one runs.
 *
 * The enum stays because it is the shared vocabulary: the receiver, the alarm scheduler, the
 * digest generator and the Settings preview all name slots through it. It is kept nested in an
 * object of the same name so every existing `SmartNotificationWorker.Slot` reference still
 * reads the same.
 */
object SmartNotificationWorker {

    const val KEY_SLOT = "slot"

    enum class Slot(
        val channelId: String,
        val title: String,
        val notificationId: Int
    ) {
        MORNING("smart_morning", "Good morning, Carl", 2001),
        MIDDAY("smart_midday", "Midday check-in", 2002),
        AFTERNOON("smart_afternoon", "Afternoon check-in", 2003),
        EVENING("smart_evening", "Evening prep", 2004)
    }
}
