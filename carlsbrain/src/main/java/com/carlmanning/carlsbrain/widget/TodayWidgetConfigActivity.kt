package com.carlmanning.carlsbrain.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.carlmanning.carlsbrain.data.local.ErrorLog
import com.carlmanning.carlsbrain.data.today.TodaySurfaces
import com.carlmanning.carlsbrain.ui.theme.CarlsBrainTheme
import kotlinx.coroutines.launch

/**
 * Settings for one placed Today widget.
 *
 * Shown when the widget is added, and again from its long-press menu. Per widget rather than
 * in app Settings because app Settings cannot know which widget is meant — two on different
 * home pages may quite reasonably be set up differently.
 *
 * It shows no to-dos, notes or anything else of Carl's, which is why it sits outside the
 * biometric gate: it is opened by the launcher, not from inside the app.
 */
class TodayWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Backing out must still leave a working widget with the defaults, not cancel the
        // placement — the options are optional, the widget is not.
        setResult(RESULT_OK, result)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            CarlsBrainTheme {
                Surface {
                    ConfigScreen(appWidgetId = appWidgetId, onDone = { finish() })
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(appWidgetId: Int, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(TodayWidgetPrefs.Options()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(appWidgetId) {
        runCatching {
            val id = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            options = TodayWidgetPrefs.read(
                getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
            )
        }.onFailure { ErrorLog.record("TodayWidgetConfig/load", it) }
        loaded = true
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            // Clear of the status bar without depending on any particular insets API.
            .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Today widget", style = MaterialTheme.typography.headlineSmall)
        Text(
            "When space runs out the widget drops the briefing first, then the calendar " +
                "line, then extra items. The next thing and the capture buttons always stay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Extra items below the next thing")
                Text(
                    "Up to this many, if they fit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = { options = options.copy(extraItems = (options.extraItems - 1).coerceAtLeast(0)) }
            ) { Text("-") }
            Text(
                options.extraItems.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            OutlinedButton(
                onClick = { options = options.copy(extraItems = (options.extraItems + 1).coerceAtMost(6)) }
            ) { Text("+") }
        }

        HorizontalDivider()
        Toggle("Weather", "One thin line at the top: Dubbo now, high and low", options.weather) {
            options = options.copy(weather = it)
        }
        Toggle("Briefing line", "One sentence from this morning's briefing", options.briefing) {
            options = options.copy(briefing = it)
        }
        Toggle("Tomorrow's to-dos", "Listed after everything for today", options.tomorrow) {
            options = options.copy(tomorrow = it)
        }
        Toggle("Next calendar event", "Only the next one, not the whole day", options.calendar) {
            options = options.copy(calendar = it)
        }
        Toggle("Capture buttons", "Voice, To-do, Note, Meeting — kept even when space is short", options.capture) {
            options = options.copy(capture = it)
        }
        Toggle("Done-today count", "Small progress number", options.counter) {
            options = options.copy(counter = it)
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Theme", modifier = Modifier.weight(1f))
            FilterChip(
                selected = options.dark,
                onClick = { options = options.copy(dark = true) },
                label = { Text("Dark") }
            )
            FilterChip(
                selected = !options.dark,
                onClick = { options = options.copy(dark = false) },
                label = { Text("Light") }
            )
        }

        Button(
            enabled = loaded,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val chosen = options
                scope.launch {
                    runCatching {
                        val id = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
                        updateAppWidgetState(context, id) { prefs ->
                            prefs[TodayWidgetPrefs.EXTRA_ITEMS] = chosen.extraItems
                            prefs[TodayWidgetPrefs.BRIEFING] = chosen.briefing
                            prefs[TodayWidgetPrefs.CALENDAR] = chosen.calendar
                            prefs[TodayWidgetPrefs.CAPTURE] = chosen.capture
                            prefs[TodayWidgetPrefs.COUNTER] = chosen.counter
                            prefs[TodayWidgetPrefs.DARK] = chosen.dark
                            prefs[TodayWidgetPrefs.TOMORROW] = chosen.tomorrow
                            prefs[TodayWidgetPrefs.WEATHER] = chosen.weather
                        }
                        TodaySurfaces.refresh(context)
                    }.onFailure { ErrorLog.record("TodayWidgetConfig/save", it) }
                    onDone()
                }
            }
        ) { Text("Save") }
    }
}

@Composable
private fun Toggle(label: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
