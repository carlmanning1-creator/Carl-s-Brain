package com.carlmanning.carlsbrain.ui.screens.journal

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.domain.journal.JournalTrends
import com.carlmanning.carlsbrain.ui.components.EmptyState
import com.carlmanning.carlsbrain.util.formatSmartDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

/**
 * Charts the numbers Carl has been recording in templated journal entries.
 *
 * The data model was built for this in v2.8 — stable field ids, per-entry field snapshots,
 * `higherIsBetter` for reversed scales — and it has been accumulating ever since with nothing
 * reading it. All of the thinking lives in [JournalTrends]; this draws it.
 *
 * Everything is local: no Claude call, no network. A chart that cost money per look would not
 * get looked at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val vaultOpen = MutableStateFlow(false)

    /** Null charts every template at once, which is how a shared field becomes one series. */
    private val selectedTemplate = MutableStateFlow<Long?>(null)

    fun setVaultVisible(open: Boolean) { vaultOpen.value = open }
    fun selectTemplate(id: Long?) { selectedTemplate.value = id }

    // The vault-closed query is the default. Filtering is in SQL, not here: a point on a line
    // has no title, so a leaked entry would be even harder to notice than a leaked row.
    private val answered = vaultOpen.flatMapLatest { open ->
        if (open) db.journalDao().getAllAnswered() else db.journalDao().getVisibleAnswered()
    }

    val state: StateFlow<TrendsState> =
        combine(answered, selectedTemplate) { entries, templateId ->
            TrendsState(
                templates = JournalTrends.templatesWithData(entries),
                selectedTemplateId = templateId,
                series = JournalTrends.seriesFrom(entries, templateId),
                entryCount = entries.size
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsState())
}

data class TrendsState(
    val templates: List<Pair<Long, String>> = emptyList(),
    val selectedTemplateId: Long? = null,
    val series: List<JournalTrends.Series> = emptyList(),
    val entryCount: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrendsScreen(
    onBack: () -> Unit,
    isVaultVisible: Boolean = false,
    viewModel: TrendsViewModel = viewModel()
) {
    androidx.compose.runtime.LaunchedEffect(isVaultVisible) {
        viewModel.setVaultVisible(isVaultVisible)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trends") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (state.series.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Filled.ShowChart,
                    title = "Nothing to chart yet",
                    // Says what is missing rather than just that something is. Two entries is
                    // the honest minimum — one point is a dot, not a trend.
                    subtitle = if (state.entryCount == 0) {
                        "Write a couple of entries from a template with scales — Training or " +
                            "Kink — and their scores will appear here."
                    } else {
                        "You have ${state.entryCount} templated ${if (state.entryCount == 1) "entry" else "entries"}, " +
                            "but a chart needs at least two answers to the same question."
                    }
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (state.templates.size > 1) {
                item {
                    // "All" is first and is the interesting one: a field id shared between two
                    // templates plots as a single series, which is what makes training scores
                    // and sleep comparable rather than two separate charts.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.selectedTemplateId == null,
                            onClick = { viewModel.selectTemplate(null) },
                            label = { Text("All") }
                        )
                        state.templates.forEach { (id, name) ->
                            FilterChip(
                                selected = state.selectedTemplateId == id,
                                onClick = { viewModel.selectTemplate(id) },
                                label = { Text(name) }
                            )
                        }
                    }
                }
            }

            items(state.series, key = { it.fieldId }) { series ->
                SeriesCard(series)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SeriesCard(series: JournalTrends.Series) {
    val lineColour = MaterialTheme.colorScheme.primary
    val gridColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val change = remember(series) { series.changeOverLast(30) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = series.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${series.points.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                val range = (series.max - series.min).coerceAtLeast(1).toFloat()
                val points = series.points

                // Plotted against the calendar, not against position in the list: three entries
                // in a week and then a month's gap should look like a gap, or the shape lies.
                val firstAt = points.first().atMs
                val span = (points.last().atMs - firstAt).coerceAtLeast(1L).toFloat()

                fun xFor(atMs: Long) = ((atMs - firstAt) / span) * size.width
                fun yFor(value: Int) = size.height - ((value - series.min) / range) * size.height

                // Faint guides at the two ends of the scale, so a line sitting high or low is
                // readable without axis labels crowding a small chart.
                listOf(series.min, series.max).forEach { level ->
                    val y = yFor(level)
                    drawLine(gridColour, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }

                val path = Path().apply {
                    points.forEachIndexed { index, point ->
                        val x = xFor(point.atMs)
                        val y = yFor(point.value)
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(path, lineColour, style = Stroke(width = 3f))

                points.forEach { point ->
                    drawCircle(lineColour, radius = 4f, center = Offset(xFor(point.atMs), yFor(point.value)))
                }
            }

            // The anchors are not decoration — a bare number is uninterpretable a year later,
            // which is why the template model insists on them.
            if (series.minAnchor.isNotBlank() || series.maxAnchor.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${series.min} · ${series.minAnchor}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${series.max} · ${series.maxAnchor}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Stat("Average", String.format("%.1f", series.average))
                series.best?.let { Stat("Best", it.toString()) }
                series.worst?.let { Stat("Worst", it.toString()) }
            }

            Text(
                text = when {
                    // Positive always means better, reversed scales included — see Series.
                    change == null ->
                        "Not enough entries either side of the last month to call a trend."
                    abs(change) < 0.5 -> "Steady over the last month."
                    change > 0 -> "Up ${String.format("%.1f", change)} over the last month."
                    else -> "Down ${String.format("%.1f", abs(change))} over the last month."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "${formatSmartDate(series.points.first().atMs)} – " +
                    formatSmartDate(series.points.last().atMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.titleSmall)
    }
}
