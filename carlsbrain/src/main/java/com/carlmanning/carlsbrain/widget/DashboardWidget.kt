package com.carlmanning.carlsbrain.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.CalendarEventEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.usecase.CompleteTodoUseCase
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home-screen mirror of the in-app Dashboard, in the same order the app uses:
 * busy-mode banner → briefing → Overdue → Needs attention → Today's next events.
 *
 * Two deliberate constraints:
 *  - The widget never calls Claude. It renders the briefing the app last generated, cached in
 *    [com.carlmanning.carlsbrain.data.preferences.UserPreferences.cachedBriefing], with a
 *    relative age so a stale line is obviously stale.
 *  - Every item list uses the vault-safe DAO queries. The cached briefing text is shown
 *    regardless of vault state — a trade-off Carl accepted knowingly.
 */
class DashboardWidget : GlanceAppWidget() {

    companion object {
        /** 4×3 and up — the current default placement. */
        private val SMALL = DpSize(250.dp, 180.dp)

        /** Anything taller gets the full set of sections. */
        private val LARGE = DpSize(250.dp, 300.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // All reads happen here, inside the provideGlance coroutine — never on the main thread.
        val db = AppDatabase.getInstance(context)
        val prefs = CarlsBrainApp.userPreferences

        val priorityTodos = db.todoDao().getUrgentHighTodosNonVault()

        val now = System.currentTimeMillis()
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 86_400_000L
        val todayEvents = db.calendarEventDao().getEventsForDay(dayStart, dayEnd)

        val busyModeActive = prefs.busyModeActive.first()
        val briefing = prefs.cachedBriefing.first()
        val briefingAt = prefs.cachedBriefingAt.first()

        // Overdue rows at any priority, so the titles shown always match the count. Deriving
        // them from the urgent/high list would omit overdue Normal/Someday items and leave the
        // widget claiming more overdue than it lists.
        val overdueTodos = db.todoDao().getOverdueNonVault(now)
        val attentionTodos = priorityTodos.filterNot { todo -> overdueTodos.any { it.id == todo.id } }
        val upcomingEvents = todayEvents
            .filter { !it.isAllDay && it.endMs > now }
            .sortedBy { it.startMs }

        provideContent {
            GlanceTheme {
                DashboardWidgetContent(
                    context = context,
                    busyModeActive = busyModeActive,
                    briefing = briefing,
                    briefingAt = briefingAt,
                    overdueCount = overdueTodos.size,
                    overdueTodos = overdueTodos,
                    attentionTodos = attentionTodos,
                    upcomingEvents = upcomingEvents
                )
            }
        }
    }
}

@Composable
private fun DashboardWidgetContent(
    context: Context,
    busyModeActive: Boolean,
    briefing: String,
    briefingAt: Long,
    overdueCount: Int,
    overdueTodos: List<TodoEntity>,
    attentionTodos: List<TodoEntity>,
    upcomingEvents: List<CalendarEventEntity>
) {
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    val today = dateFmt.format(Date())

    // Responsive breakpoint. The small layout keeps the same order but shows fewer rows and a
    // shorter briefing; the large one shows every section that has content.
    val isLarge = LocalSize.current.height >= 240.dp
    val briefingLines = if (isLarge) 3 else 2
    val overdueTitleRows = if (isLarge) 2 else 1
    val attentionRows = if (isLarge) 4 else 2
    val eventRows = if (isLarge) 3 else 2

    val hasBriefing = briefing.isNotBlank()
    val hasOverdue = overdueCount > 0
    val hasAttention = attentionTodos.isNotEmpty()
    val hasEvents = upcomingEvents.isNotEmpty()

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .clickable(actionStartActivity(openAppIntent))
            .padding(12.dp)
    ) {
        // Header — date + manual refresh
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = today,
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = "↻",
                style = TextStyle(color = GlanceTheme.colors.secondary, fontSize = 14.sp),
                modifier = GlanceModifier
                    .padding(horizontal = 4.dp)
                    .clickable(actionRunCallback<RefreshDashboardAction>())
            )
        }

        // ── Busy mode ─────────────────────────────────────────────
        // Above everything, exactly as in the app: a mode that quietly silences notifications
        // must never be something Carl has to go looking for.
        if (busyModeActive) {
            Text(
                text = "Busy mode · notifications paused",
                style = TextStyle(
                    color = GlanceTheme.colors.error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.padding(top = 4.dp),
                maxLines = 1
            )
        }

        // ── Briefing ──────────────────────────────────────────────
        if (hasBriefing) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = briefing,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                maxLines = briefingLines
            )
            val age = relativeAge(briefingAt)
            if (age != null) {
                Text(
                    text = age,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp),
                    maxLines = 1
                )
            }
        }

        // ── Overdue ───────────────────────────────────────────────
        if (hasOverdue) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = if (overdueCount == 1) "1 overdue" else "$overdueCount overdue",
                style = TextStyle(
                    color = GlanceTheme.colors.error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            overdueTodos.take(overdueTitleRows).forEach { todo ->
                Text(
                    text = todo.title,
                    style = TextStyle(color = GlanceTheme.colors.error, fontSize = 11.sp),
                    modifier = GlanceModifier.padding(top = 1.dp),
                    maxLines = 1
                )
            }
        }

        // ── Needs attention ───────────────────────────────────────
        if (hasAttention) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = "NEEDS ATTENTION",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            attentionTodos.take(attentionRows).forEach { todo ->
                val isUrgent = todo.priority == Priority.URGENT.rank
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ticking this goes through CompleteTodoUseCase, so a recurring to-do still
                    // spawns its next occurrence — see CompleteTodoAction.
                    CheckBox(
                        checked = false,
                        onCheckedChange = actionRunCallback<CompleteTodoAction>(
                            actionParametersOf(CompleteTodoAction.TODO_ID to todo.id)
                        ),
                        text = ""
                    )
                    Text(
                        text = if (isUrgent) "!!" else "!",
                        style = TextStyle(
                            color = if (isUrgent) GlanceTheme.colors.error else GlanceTheme.colors.secondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = todo.title,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp),
                        maxLines = 1
                    )
                }
            }
        }

        // ── Today's next events ───────────────────────────────────
        if (hasEvents) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = "TODAY",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            upcomingEvents.take(eventRows).forEach { event ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeFmt.format(Date(event.startMs)),
                        style = TextStyle(
                            color = GlanceTheme.colors.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = event.title,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp),
                        maxLines = 1
                    )
                }
            }
        }

        // Nothing at all to show — a neutral line beats a blank widget.
        if (!busyModeActive && !hasBriefing && !hasOverdue && !hasAttention && !hasEvents) {
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = "Nothing needs attention",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
            )
        }
    }
}

/**
 * Short "5m ago" / "2h ago" / "3d ago" hint for the cached briefing.
 * Null when there is no timestamp or the briefing is under a minute old — a fresh line needs
 * no caveat. `util/DateFormat.kt` only formats absolute dates, so this stays local.
 */
private fun relativeAge(atMs: Long, now: Long = System.currentTimeMillis()): String? {
    if (atMs <= 0L) return null
    val minutes = (now - atMs).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1L -> null
        minutes < 60L -> "${minutes}m ago"
        minutes < 1440L -> "${minutes / 60L}h ago"
        else -> "${minutes / 1440L}d ago"
    }
}

/** Re-reads the data and redraws. Bound to the header's ↻. */
class RefreshDashboardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        DashboardWidget().update(context, glanceId)
    }
}

/**
 * Completes a to-do from the widget.
 *
 * Goes through [CompleteTodoUseCase] rather than `setTodoDone` so a recurring to-do still spawns
 * its next occurrence — a direct DAO write would break recurrence silently, and only from here.
 * There is no Undo affordance on a widget, so the spawned id is deliberately discarded.
 */
class CompleteTodoAction : ActionCallback {
    companion object {
        val TODO_ID = ActionParameters.Key<Long>("todoId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val todoId = parameters[TODO_ID] ?: return
        CompleteTodoUseCase(context.applicationContext).markDone(todoId, true)
        // Redraw every instance so the row disappears wherever the widget is placed.
        DashboardWidget().updateAll(context)
    }
}

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}
