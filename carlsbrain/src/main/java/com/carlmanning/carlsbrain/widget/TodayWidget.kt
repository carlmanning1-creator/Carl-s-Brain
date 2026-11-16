package com.carlmanning.carlsbrain.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.ui.capture.QuickAddActivity
import com.carlmanning.carlsbrain.R
import com.carlmanning.carlsbrain.data.local.worker.AmbientBufferService
import com.carlmanning.carlsbrain.data.today.TodayActions
import com.carlmanning.carlsbrain.data.today.TodayItem
import com.carlmanning.carlsbrain.data.today.TodayRepository
import com.carlmanning.carlsbrain.data.today.TodaySnapshot
import com.carlmanning.carlsbrain.data.today.TodayState
import com.carlmanning.carlsbrain.data.today.TodaySurfaces
import com.carlmanning.carlsbrain.domain.today.NextKind
import com.carlmanning.carlsbrain.domain.today.WidgetFit
import com.carlmanning.carlsbrain.ui.VoiceCaptureActivity

/**
 * The Today widget: the next thing, a tick for it, and quick capture.
 *
 * Replaces both earlier widgets (November 2026). Built to Carl's approved mock-up — its
 * colours, sizes and order of sacrifice — with three differences a home-screen widget cannot
 * avoid: no letter-spacing on the "NEXT" label, bold rather than true semibold, and the
 * phone's own font rather than Inter.
 *
 * Rules worth keeping:
 *  - **It renders [TodaySnapshot] and decides nothing.** What is next is NextThingRanker's
 *    answer, shared with the notifications, so the surfaces cannot disagree.
 *  - **Layout comes from the real height** the launcher reports ([SizeMode.Exact]), through
 *    [WidgetFit]. Never from a remembered "4x3".
 *  - **Data is loaded inside the composition, keyed on a version** written by
 *    [TodaySurfaces.refresh]. A Glance session outlives a single update; values read once in
 *    provideGlance would go on being drawn after a tick, with the ticked row still there.
 *  - **Never calls Claude, never touches the network.** The briefing line is the cached one.
 *  - Vault-safe because the snapshot is; nothing here filters.
 */
open class TodayWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext
        // Loaded here as well, so the very first frame is real rather than a blank card.
        val initial = runCatching { TodayRepository.load(app) }.getOrNull()
        // The half-hourly widget update lands here and nowhere else, so this is what keeps the
        // weather line current with the app closed. Off the render: it updates the cache.
        TodaySurfaces.refreshWeatherIfStale(app)
        provideContent {
            val state = currentState<Preferences>()
            val version = state[TodayWidgetPrefs.VERSION] ?: 0L
            val snapshot by produceState(initial, version) {
                value = runCatching { TodayRepository.load(app) }.getOrNull() ?: value
            }
            TodayContent(app, snapshot, TodayWidgetPrefs.read(state))
        }
    }
}

/** Per-widget settings, held in that widget's own Glance state. */
object TodayWidgetPrefs {
    /** Bumped by every refresh; the composition reloads its data when it changes. */
    val VERSION = longPreferencesKey("today_version")
    val EXTRA_ITEMS = intPreferencesKey("today_extra_items")
    val BRIEFING = booleanPreferencesKey("today_briefing")
    val CALENDAR = booleanPreferencesKey("today_calendar")
    val CAPTURE = booleanPreferencesKey("today_capture")
    val COUNTER = booleanPreferencesKey("today_counter")
    val DARK = booleanPreferencesKey("today_dark")
    val TOMORROW = booleanPreferencesKey("today_tomorrow")
    val WEATHER = booleanPreferencesKey("today_weather")

    data class Options(
        // Five, not three: with tomorrow included there is more worth seeing, and a 4x5
        // otherwise left a third of itself empty (Carl, November 2026).
        val extraItems: Int = 5,
        val briefing: Boolean = true,
        val calendar: Boolean = true,
        val capture: Boolean = true,
        val counter: Boolean = true,
        val dark: Boolean = true,
        val tomorrow: Boolean = true,
        val weather: Boolean = true
    )

    fun read(state: Preferences) = Options(
        extraItems = (state[EXTRA_ITEMS] ?: 5).coerceIn(0, 6),
        briefing = state[BRIEFING] ?: true,
        calendar = state[CALENDAR] ?: true,
        capture = state[CAPTURE] ?: true,
        counter = state[COUNTER] ?: true,
        dark = state[DARK] ?: true,
        tomorrow = state[TOMORROW] ?: true,
        weather = state[WEATHER] ?: true
    )
}

// ── Palette (from the mock-up) ────────────────────────────────────────────────

private class Palette(
    val bg: Color, val text: Color, val muted: Color, val line: Color, val soft: Color,
    val overdue: Color, val urgent: Color, val today: Color, val tickBg: Color, val tickFg: Color
) {
    fun meta(kind: NextKind) = when (kind) {
        NextKind.OVERDUE -> overdue
        NextKind.URGENT -> urgent
        NextKind.DUE_TODAY -> today
        NextKind.DUE_TOMORROW -> muted
    }
}

private val DARK = Palette(
    bg = Color(0xFF1E2228), text = Color(0xFFF3F4F6), muted = Color(0xFFA7AFBA),
    line = Color(0xFF2E343C), soft = Color(0xFF2A2F36), overdue = Color(0xFFF2A541),
    urgent = Color(0xFFFF9A8B), today = Color(0xFF8AB4F8),
    tickBg = Color(0xFFF3F4F6), tickFg = Color(0xFF1E2228)
)

private val LIGHT = Palette(
    bg = Color(0xFFFFFFFF), text = Color(0xFF15181C), muted = Color(0xFF5B6470),
    line = Color(0xFFE6E8EB), soft = Color(0xFFF2F3F5), overdue = Color(0xFF9A5B00),
    urgent = Color(0xFFB3261E), today = Color(0xFF1A5FB4),
    tickBg = Color(0xFF15181C), tickFg = Color(0xFFFFFFFF)
)

private fun style(color: Color, size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(color = ColorProvider(color), fontSize = size.sp, fontWeight = weight)

// ── What is drawn in each position ────────────────────────────────────────────

/** A to-do, or the "Marked done · Undo" that has taken a ticked to-do's place. */
private sealed class Slot {
    class Todo(val item: TodayItem) : Slot()
    class Undone(val undo: TodayState.Undo) : Slot()
}

private fun slots(snapshot: TodaySnapshot, tomorrow: Boolean): List<Slot> {
    // Tomorrow comes after everything for today, so it can never be "next" while today still
    // has something — but an all-clear today shows what is coming rather than nothing.
    val items = if (tomorrow) snapshot.items + snapshot.tomorrow else snapshot.items
    val list = items.map<TodayItem, Slot> { Slot.Todo(it) }.toMutableList()
    val undo = snapshot.undo
    if (undo != null) list.add(undo.row.coerceIn(0, list.size), Slot.Undone(undo))
    return list
}

@Composable
private fun TodayContent(context: Context, snapshot: TodaySnapshot?, options: TodayWidgetPrefs.Options) {
    val p = if (options.dark) DARK else LIGHT
    val frame = GlanceModifier
        .fillMaxSize()
        .background(p.bg)
        .cornerRadius(28.dp)
        .padding(16.dp)

    if (snapshot == null) {
        // The database could not be read. Say so rather than drawing "All clear", which
        // would be the widget asserting something it did not manage to check.
        Column(modifier = frame) {
            Text("Couldn't load just now", style = style(p.muted, 13))
        }
        return
    }

    val all = slots(snapshot, options.tomorrow)
    val fit = WidgetFit.compute(
        heightDp = LocalSize.current.height.value,
        fontScale = context.resources.configuration.fontScale,
        options = WidgetFit.Options(
            extraItems = options.extraItems,
            briefing = options.briefing,
            calendar = options.calendar,
            capture = options.capture,
            weather = options.weather
        ),
        hasCalendar = snapshot.nextEvent != null,
        hasBriefing = snapshot.briefingLine != null,
        hasWeather = snapshot.weatherLine != null,
        available = (all.size - 1).coerceAtLeast(0)
    )

    // Glance renders at most TEN children per Column and silently drops the rest. The first
    // version put every line straight into one Column — briefing, spacers, the next block's
    // pieces, the calendar, each divider and row, the capture bar — and passed ten as soon as
    // there were two extra rows. Everything after the cut vanished: the remaining rows *and*
    // the capture bar, leaving half the widget empty. So: gaps are padding, not Spacers, and
    // each section is one child. The outer Column now has exactly two children, the content
    // and the capture bar, and the capture bar sits outside the part that can run out of room.
    Column(modifier = frame) {
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            if (fit.showWeather) {
                Text(
                    text = snapshot.weatherLine.orEmpty(),
                    style = style(p.muted, 12),
                    maxLines = 1,
                    modifier = GlanceModifier.padding(bottom = 6.dp)
                )
            }
            if (fit.showBriefing) {
                Text(
                    text = snapshot.briefingLine.orEmpty(),
                    style = style(p.muted, 13),
                    maxLines = 1,
                    modifier = GlanceModifier.padding(bottom = 10.dp)
                )
            }

            if (fit.showNext) {
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    NextBlock(
                        context, p, all.firstOrNull(), snapshot.doneToday, options.counter,
                        fit.nextTwoLines, snapshot.toSort
                    )
                }
            }

            if (fit.showCalendar) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .background(p.soft)
                            .cornerRadius(12.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_today_calendar),
                            contentDescription = null,
                            modifier = GlanceModifier.size(16.dp),
                            colorFilter = ColorFilter.tint(ColorProvider(p.text))
                        )
                        Spacer(GlanceModifier.width(8.dp))
                        Text(snapshot.nextEvent.orEmpty(), style = style(p.text, 13), maxLines = 1)
                    }
                }
            }

            if (fit.extraRows > 0) {
                // One child for all the rows (at most six), each row one child of its own.
                Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 10.dp)) {
                    all.drop(1).take(fit.extraRows).forEachIndexed { index, slot ->
                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                            Box(GlanceModifier.fillMaxWidth().height(1.dp).background(p.line)) {}
                            ExtraRow(context, p, slot, row = index + 1)
                        }
                    }
                }
            }
        }

        if (fit.showCapture) {
            Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 10.dp)) {
                CaptureBar(context, p, fit.captureCompact)
            }
        }
    }
}

@Composable
private fun NextBlock(
    context: Context,
    p: Palette,
    slot: Slot?,
    doneToday: Int,
    showCounter: Boolean,
    twoLines: Boolean,
    toSort: Int
) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when (slot) {
                null -> "ALL CLEAR"
                is Slot.Undone -> "DONE"
                is Slot.Todo -> "NEXT"
            },
            style = style(p.muted, 11, FontWeight.Bold)
        )
        Spacer(GlanceModifier.defaultWeight())
        // To-dos with no date and no urgency can never be "next", so without this they are
        // simply absent from the widget. Shown only when there are some: a pill that is
        // always there stops being read. Sits in the label row, so it costs no height and
        // WidgetFit knows nothing about it.
        if (toSort > 0) {
            Box(
                modifier = GlanceModifier
                    .background(p.soft)
                    .cornerRadius(10.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .clickable(
                        actionStartActivity(
                            Intent(context, MainActivity::class.java).apply {
                                action = MainActivity.ACTION_OPEN_TODOS_TO_SORT
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                        )
                    )
            ) {
                Text("$toSort to sort", style = style(p.overdue, 12, FontWeight.Medium))
            }
            if (showCounter) Spacer(GlanceModifier.width(6.dp))
        }
        if (showCounter) {
            Box(
                modifier = GlanceModifier
                    .background(p.soft)
                    .cornerRadius(10.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("$doneToday done today", style = style(p.text, 12, FontWeight.Medium))
            }
        }
    }
    Spacer(GlanceModifier.height(4.dp))

    when (slot) {
        null -> Text(
            "Nothing overdue or due today.",
            style = style(p.text, 19, FontWeight.Bold),
            maxLines = 2
        )
        is Slot.Undone -> Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text("Marked done", style = style(p.text, 19, FontWeight.Bold), maxLines = 1)
                Text(slot.undo.title, style = style(p.muted, 12, FontWeight.Medium), maxLines = 1)
            }
            UndoButton(p, slot.undo.todoId)
        }
        is Slot.Todo -> Row(modifier = GlanceModifier.fillMaxWidth()) {
            // 48dp to touch, 44dp to look at.
            Box(
                modifier = GlanceModifier.size(48.dp).clickable(tick(slot.item.id, 0)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = GlanceModifier.size(44.dp).background(p.tickBg).cornerRadius(22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_today_check),
                        contentDescription = "Mark done: ${slot.item.title}",
                        modifier = GlanceModifier.size(20.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(p.tickFg))
                    )
                }
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight().clickable(open(context, slot.item.id))) {
                Text(
                    slot.item.title,
                    style = style(p.text, 19, FontWeight.Bold),
                    maxLines = if (twoLines) 2 else 1
                )
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    slot.item.meta,
                    style = style(p.meta(slot.item.kind), 12, FontWeight.Medium),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ExtraRow(context: Context, p: Palette, slot: Slot, row: Int) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (slot) {
            is Slot.Undone -> {
                Text(
                    "Marked done",
                    style = style(p.text, 14, FontWeight.Medium),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight().padding(start = 6.dp)
                )
                UndoButton(p, slot.undo.todoId)
            }
            is Slot.Todo -> {
                Box(
                    modifier = GlanceModifier.size(48.dp).clickable(tick(slot.item.id, row)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_today_ring),
                        contentDescription = "Mark done: ${slot.item.title}",
                        modifier = GlanceModifier.size(24.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(p.muted))
                    )
                }
                Spacer(GlanceModifier.width(4.dp))
                Column(modifier = GlanceModifier.defaultWeight().clickable(open(context, slot.item.id))) {
                    Text(slot.item.title, style = style(p.text, 14, FontWeight.Medium), maxLines = 1)
                    Text(
                        slot.item.meta,
                        style = style(p.meta(slot.item.kind), 12, FontWeight.Medium),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun UndoButton(p: Palette, todoId: Long) {
    Box(
        modifier = GlanceModifier
            .height(44.dp)
            .background(p.tickBg)
            .cornerRadius(10.dp)
            .padding(horizontal = 16.dp)
            .clickable(
                actionRunCallback<TodayUndoAction>(
                    actionParametersOf(TodayDoneAction.TODO_ID to todoId)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text("Undo", style = style(p.tickFg, 14, FontWeight.Bold))
    }
}

@Composable
private fun CaptureBar(context: Context, p: Palette, compact: Boolean) {
    fun main(action: String) = Intent(context, MainActivity::class.java).apply {
        this.action = action
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val voice = Intent(context, VoiceCaptureActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        CaptureButton(p, compact, R.drawable.ic_today_voice, "Voice", actionStartActivity(voice))
        Spacer(GlanceModifier.width(8.dp))
        // To-do and Note open the write-only QuickAddActivity, not MainActivity, so adding
        // something does not cost a biometric prompt. It shows nothing and files non-vault only.
        CaptureButton(
            p, compact, R.drawable.ic_today_todo, "To-do",
            actionStartActivity(QuickAddActivity.intent(context, note = false))
        )
        Spacer(GlanceModifier.width(8.dp))
        CaptureButton(
            p, compact, R.drawable.ic_today_note, "Note",
            actionStartActivity(QuickAddActivity.intent(context, note = true))
        )
        Spacer(GlanceModifier.width(8.dp))
        CaptureButton(
            p, compact, R.drawable.ic_today_meeting, "Meeting",
            actionStartActivity(main(MainActivity.ACTION_START_MEETING))
        )
    }
}

@Composable
private fun RowScope.CaptureButton(p: Palette, compact: Boolean, icon: Int, label: String, onClick: Action) {
    Column(
        modifier = GlanceModifier
            .defaultWeight()
            .height(if (compact) 44.dp else 54.dp)
            .background(p.soft)
            .cornerRadius(14.dp)
            .clickable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = label,
            modifier = GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(ColorProvider(p.text))
        )
        if (!compact) {
            Spacer(GlanceModifier.height(3.dp))
            Text(label, style = style(p.text, 11, FontWeight.Bold), maxLines = 1)
        }
    }
}

private fun tick(todoId: Long, row: Int): Action = actionRunCallback<TodayDoneAction>(
    actionParametersOf(TodayDoneAction.TODO_ID to todoId, TodayDoneAction.ROW to row)
)

private fun open(context: Context, todoId: Long): Action = actionStartActivity(
    Intent(context, MainActivity::class.java).apply {
        // The data URI keeps one to-do's intent distinct from the next row's.
        data = Uri.parse("carlsbrain://today/open/$todoId")
        putExtra(MainActivity.EXTRA_OPEN_TODO_ID, todoId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
)

// ── Actions ───────────────────────────────────────────────────────────────────

/**
 * A tick on the widget. Runs with the app process dead — Glance starts it — and goes through
 * [TodayActions], the same path as the notifications, so recurrence and undo behave the same
 * wherever the tick was.
 */
class TodayDoneAction : ActionCallback {
    companion object {
        val TODO_ID = ActionParameters.Key<Long>("todoId")
        val ROW = ActionParameters.Key<Int>("row")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val todoId = parameters[TODO_ID] ?: return
        TodayActions.done(context, todoId, parameters[ROW] ?: 0)
    }
}

class TodayUndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val todoId = parameters[TodayDoneAction.TODO_ID] ?: return
        TodayActions.undo(context, todoId)
    }
}

// ── Providers ─────────────────────────────────────────────────────────────────

/**
 * The widget, under the provider name it has always had. Renaming the receiver would remove
 * every Dashboard widget already on Carl's home screen, so the class name stays and only what
 * it draws has changed.
 */
class DashboardWidget : TodayWidget()

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}

// ── Callbacks an already-placed widget may still be holding ───────────────────
// A home screen keeps the last RemoteViews it was given until the next update, and those name
// these classes. They stay, doing the right thing, until that update has certainly happened.

class CompleteTodoAction : ActionCallback {
    companion object {
        val TODO_ID = ActionParameters.Key<Long>("todoId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val todoId = parameters[TODO_ID] ?: return
        TodayActions.done(context, todoId)
    }
}

class RefreshDashboardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        TodaySurfaces.refresh(context)
    }
}

class ToggleBufferRecordingAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        AmbientBufferService.send(context, AmbientBufferService.ACTION_TOGGLE)
    }
}

/** Every placed Today widget, whichever provider it was added through. */
internal suspend fun updateAllTodayWidgets(context: Context) {
    val manager = GlanceAppWidgetManager(context)
    val now = System.currentTimeMillis()
    listOf(DashboardWidget()).forEach { widget ->
        manager.getGlanceIds(widget.javaClass).forEach { id ->
            updateAppWidgetState(context, id) { it[TodayWidgetPrefs.VERSION] = now }
            widget.update(context, id)
        }
    }
}
