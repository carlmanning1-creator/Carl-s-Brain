package com.carlmanning.carlsbrain.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import androidx.glance.action.ActionCallback
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.CalendarEventEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DashboardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AppDatabase.getInstance(context)
        val todos = db.todoDao().getUrgentHighTodosNonVault()
        val overdueCount = db.todoDao().getOverdueCountNonVault()

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val dayStart = cal.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 86_400_000L
        val todayEvents = db.calendarEventDao().getEventsForDay(dayStart, dayEnd)

        provideContent {
            GlanceTheme {
                DashboardWidgetContent(
                    context = context,
                    todos = todos,
                    overdueCount = overdueCount,
                    todayEvents = todayEvents
                )
            }
        }
    }
}

@Composable
private fun DashboardWidgetContent(
    context: Context,
    todos: List<TodoEntity>,
    overdueCount: Int,
    todayEvents: List<CalendarEventEntity>
) {
    val openAppIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    val today = dateFmt.format(Date())

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .clickable(actionStartActivity(openAppIntent))
            .padding(12.dp)
    ) {
        // Header row: date + refresh button
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
                style = TextStyle(
                    color = GlanceTheme.colors.secondary,
                    fontSize = 14.sp
                ),
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshDashboardAction>())
            )
        }

        // Overdue badge
        if (overdueCount > 0) {
            Text(
                text = "⚠ $overdueCount overdue",
                style = TextStyle(
                    color = GlanceTheme.colors.error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier.padding(top = 2.dp)
            )
        }

        Spacer(GlanceModifier.height(6.dp))

        // Today's calendar events (up to 2)
        val upcomingEvents = todayEvents
            .filter { !it.isAllDay && it.endMs > System.currentTimeMillis() }
            .take(2)

        if (upcomingEvents.isNotEmpty()) {
            Text(
                text = "TODAY",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            upcomingEvents.forEach { event ->
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
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 11.sp
                        ),
                        maxLines = 1
                    )
                }
            }
            // All-day events
            val allDay = todayEvents.filter { it.isAllDay }.take(1)
            allDay.forEach { event ->
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "●",
                        style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 10.sp)
                    )
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = event.title,
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 11.sp),
                        maxLines = 1
                    )
                }
            }
            Spacer(GlanceModifier.height(6.dp))
        }

        // Priority todos (up to 4)
        if (todos.isEmpty() && overdueCount == 0) {
            Text(
                text = "No urgent tasks",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
            )
        } else if (todos.isNotEmpty()) {
            Text(
                text = "TASKS",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            todos.take(4).forEach { todo ->
                val isUrgent = todo.priority == Priority.URGENT.rank
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 11.sp
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

class RefreshDashboardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId) {
        DashboardWidget().update(context, glanceId)
    }
}

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}
