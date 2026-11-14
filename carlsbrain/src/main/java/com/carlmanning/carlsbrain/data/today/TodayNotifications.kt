package com.carlmanning.carlsbrain.data.today

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.domain.today.NextKind
import com.carlmanning.carlsbrain.ui.VoiceCaptureActivity
import kotlinx.coroutines.flow.first

/**
 * The check-ins and the pinned "Today" notification — one layout, two uses.
 *
 * Both are drawn from a [TodaySnapshot], so they name the same next thing the widget does.
 * A tick on either goes to [TodoActionReceiver], which redraws both; nothing here changes a
 * to-do.
 *
 * Vault: the snapshot is vault-safe, and so is everything on these notifications. The lock
 * screen is a separate question — who is holding the phone — and is answered by the
 * full-titles setting, off by default.
 */
object TodayNotifications {

    const val PINNED_CHANNEL_ID = "today_pinned"
    private const val PINNED_ID = 9101
    private const val MAX_ROWS = 3

    // Mid-tones that read on both a light and a dark notification shade.
    private const val COLOR_OVERDUE = 0xFFD9822B.toInt()
    private const val COLOR_URGENT = 0xFFD9534F.toInt()
    private const val COLOR_TODAY = 0xFF4A8FE7.toInt()

    private val ROW_IDS = listOf(
        intArrayOf(R.id.today_row1, R.id.today_row1_tick, R.id.today_row1_text, R.id.today_row1_title, R.id.today_row1_meta),
        intArrayOf(R.id.today_row2, R.id.today_row2_tick, R.id.today_row2_text, R.id.today_row2_title, R.id.today_row2_meta),
        intArrayOf(R.id.today_row3, R.id.today_row3_tick, R.id.today_row3_text, R.id.today_row3_title, R.id.today_row3_meta)
    )

    /** Safe to call repeatedly; creating an existing channel is a no-op. */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            PINNED_CHANNEL_ID, "Today (pinned)", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "A silent, always-there list of what is next"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun canPost(context: Context) = ActivityCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Brings both kinds of notification in line with the snapshot. Called after every action
     * and every to-do change. Never makes a sound: a redraw is not news.
     */
    suspend fun refresh(context: Context, snapshot: TodaySnapshot) {
        if (!canPost(context)) return
        refreshPinned(context, snapshot)
        refreshVisibleCheckIns(context, snapshot)
    }

    // ── Pinned ────────────────────────────────────────────────────────────────

    private suspend fun refreshPinned(context: Context, snapshot: TodaySnapshot) {
        val prefs = CarlsBrainApp.userPreferences
        val nm = NotificationManagerCompat.from(context)
        val show = prefs.pinnedTodayEnabled.first() &&
            !TodayState.pinnedDismissed(context) &&
            !(snapshot.busy && prefs.pinnedTodayHideWhenBusy.first())
        if (!show) {
            nm.cancel(PINNED_ID)
            return
        }
        ensureChannel(context)
        val fullTitles = prefs.pinnedTodayFullTitles.first()

        val title = snapshot.items.firstOrNull()?.title ?: "All clear"
        val counts = buildString {
            if (snapshot.overdueCount > 0) append("${snapshot.overdueCount} overdue · ")
            append("${snapshot.doneToday} done today")
        }
        val collapsed = RemoteViews(context.packageName, R.layout.today_notification_collapsed).apply {
            setTextViewText(R.id.today_title, title)
            setTextViewText(R.id.today_sub, counts)
        }
        val expanded = expandedViews(
            context, snapshot,
            header = counts,
            showSnooze = false,
            showCapture = true
        )

        // What a locked phone shows unless full titles are switched on: a count, no names.
        val publicVersion = NotificationCompat.Builder(context, PINNED_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Today")
            .setContentText(
                if (snapshot.items.isEmpty()) "All clear"
                else "${snapshot.items.size} to look at · ${snapshot.doneToday} done"
            )
            .build()

        val dismissed = PendingIntent.getBroadcast(
            context, PINNED_ID,
            Intent(context, TodoActionReceiver::class.java)
                .setAction(TodoActionReceiver.ACTION_PINNED_DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PINNED_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentIntent(openApp(context))
            .setDeleteIntent(dismissed)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(
                if (fullTitles) NotificationCompat.VISIBILITY_PUBLIC
                else NotificationCompat.VISIBILITY_PRIVATE
            )
            .setPublicVersion(publicVersion)
            .build()
        notify(context, PINNED_ID, notification)
    }

    // ── Check-ins ─────────────────────────────────────────────────────────────

    /**
     * Posts a check-in. Called by SmartNotificationReceiver in place of its old text-only
     * notification; quiet hours and busy mode were already decided before it got here.
     *
     * @param line the one-sentence Claude summary, kept so a later redraw need not pay for it.
     */
    suspend fun postCheckIn(context: Context, slot: SmartNotificationWorker.Slot, line: String) {
        if (!canPost(context)) return
        TodayState.setCheckInLine(context, slot.name, line)
        val snapshot = TodayRepository.load(context)
        if (snapshot.items.isEmpty() && !CarlsBrainApp.userPreferences.checkInAllClear.first()) {
            NotificationManagerCompat.from(context).cancel(slot.notificationId)
            return
        }
        notify(context, slot.notificationId, checkIn(context, slot, line, snapshot, alert = true))
    }

    /** Redraws only check-ins that are actually in the shade — never resurrects a swiped one. */
    private fun refreshVisibleCheckIns(context: Context, snapshot: TodaySnapshot) {
        val active = runCatching {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .map { it.id }.toSet()
        }.getOrDefault(emptySet())
        SmartNotificationWorker.Slot.entries
            .filter { it.notificationId in active }
            .forEach { slot ->
                val line = TodayState.checkInLine(context, slot.name)
                notify(context, slot.notificationId, checkIn(context, slot, line, snapshot, alert = false))
            }
    }

    private fun checkIn(
        context: Context,
        slot: SmartNotificationWorker.Slot,
        line: String,
        snapshot: TodaySnapshot,
        alert: Boolean
    ): Notification {
        val next = snapshot.items.firstOrNull()
        val title = next?.title ?: "All clear"
        val sub = when {
            next == null -> "Nothing overdue or due today."
            snapshot.overdueCount > 0 -> "${snapshot.overdueCount} overdue"
            else -> next.meta
        }
        val collapsed = RemoteViews(context.packageName, R.layout.today_notification_collapsed).apply {
            setTextViewText(R.id.today_title, title)
            setTextViewText(R.id.today_sub, sub)
        }
        val expanded = expandedViews(
            context, snapshot,
            header = line.ifBlank { sub },
            showSnooze = next != null,
            showCapture = false
        )
        return NotificationCompat.Builder(context, slot.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setSubText(slot.title)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentIntent(openApp(context))
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    // ── Shared body ───────────────────────────────────────────────────────────

    private fun expandedViews(
        context: Context,
        snapshot: TodaySnapshot,
        header: String,
        showSnooze: Boolean,
        showCapture: Boolean
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.today_notification_expanded)
        views.setTextViewText(R.id.today_header, header)

        val undo = snapshot.undo
        if (undo != null) {
            views.setViewVisibility(R.id.today_undo_row, View.VISIBLE)
            views.setTextViewText(R.id.today_undo_text, "Marked done: ${undo.title}")
            views.setOnClickPendingIntent(
                R.id.today_undo, action(context, TodoActionReceiver.ACTION_UNDO, undo.todoId)
            )
        }

        if (snapshot.items.isEmpty() && undo == null) {
            // One row reused as the all-clear line, with no tick to press.
            val ids = ROW_IDS[0]
            views.setViewVisibility(ids[0], View.VISIBLE)
            views.setViewVisibility(ids[1], View.INVISIBLE)
            views.setTextViewText(ids[3], "All clear")
            views.setTextViewText(ids[4], "Nothing overdue or due today.")
        }

        // With the undo row showing there is room for two rows, not three, inside the height
        // a custom notification is allowed.
        val rows = if (undo != null) MAX_ROWS - 1 else MAX_ROWS
        snapshot.items.take(rows).forEachIndexed { index, item ->
            val ids = ROW_IDS[index]
            views.setViewVisibility(ids[0], View.VISIBLE)
            views.setTextViewText(ids[3], item.title)
            views.setTextViewText(ids[4], item.meta)
            views.setTextColor(
                ids[4],
                when (item.kind) {
                    NextKind.OVERDUE -> COLOR_OVERDUE
                    NextKind.URGENT -> COLOR_URGENT
                    NextKind.DUE_TODAY -> COLOR_TODAY
                }
            )
            views.setOnClickPendingIntent(
                ids[1], action(context, TodoActionReceiver.ACTION_DONE, item.id)
            )
            views.setOnClickPendingIntent(ids[2], openTodo(context, item.id))
        }

        val next = snapshot.items.firstOrNull()
        // No snooze beside an Undo: two different things to take back on one line.
        if (showSnooze && next != null && undo == null) {
            views.setViewVisibility(R.id.today_snooze_1h, View.VISIBLE)
            views.setViewVisibility(R.id.today_snooze_tomorrow, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.today_snooze_1h, action(context, TodoActionReceiver.ACTION_SNOOZE_1H, next.id)
            )
            views.setOnClickPendingIntent(
                R.id.today_snooze_tomorrow,
                action(context, TodoActionReceiver.ACTION_SNOOZE_TOMORROW, next.id)
            )
        }
        if (showCapture) {
            views.setViewVisibility(R.id.today_capture, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.today_capture,
                PendingIntent.getActivity(
                    context, 9102,
                    Intent(context, VoiceCaptureActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        return views
    }

    /**
     * One PendingIntent per (action, to-do). The data URI is what keeps them apart: extras do
     * not count towards PendingIntent identity, so without it every tick in the list would
     * share one intent and complete whichever to-do was written last.
     */
    private fun action(context: Context, action: String, todoId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, TodoActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("carlsbrain://today/$action/$todoId"))
                .putExtra(TodoActionReceiver.EXTRA_TODO_ID, todoId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openTodo(context: Context, todoId: Long): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("carlsbrain://today/open/$todoId"))
                .putExtra(MainActivity.EXTRA_OPEN_TODO_ID, todoId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 9103,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    @Suppress("MissingPermission") // every caller has passed canPost()
    private fun notify(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
