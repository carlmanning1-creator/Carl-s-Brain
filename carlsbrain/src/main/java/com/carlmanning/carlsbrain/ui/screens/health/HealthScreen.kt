package com.carlmanning.carlsbrain.ui.screens.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.health.*
import com.carlmanning.carlsbrain.ui.components.BrainTopBar

@Composable
fun HealthScreen(
    onVaultToggle: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: HealthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Create the contract fresh in the composable — storing it in the ViewModel
    // prevents the ActivityResultLauncher from registering with the host Activity.
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> viewModel.onPermissionsResult(granted) }

    // Re-check permissions every time the screen resumes (handles the case where
    // the user granted access directly in the Health Connect app and came back).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.checkStatus()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Health",
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (uiState.permissionStatus) {
                HealthPermissionStatus.CHECKING -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                HealthPermissionStatus.NOT_SUPPORTED -> {
                    HealthInfoCard(
                        title = "Not supported",
                        message = "Health Connect is not supported on this device."
                    )
                }
                HealthPermissionStatus.NEEDS_INSTALL -> {
                    HealthInfoCard(
                        title = "Install Health Connect",
                        message = "Health Connect is required to read your health data. It's a free Google app.",
                        actionLabel = "Install",
                        onAction = {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                        }
                    )
                }
                HealthPermissionStatus.NEEDS_PERMISSION -> {
                    HealthInfoCard(
                        title = "Connect health data",
                        message = "Grant access to sleep, nutrition, weight, and steps from Health Connect so Carl's Brain can factor your health into daily recommendations.",
                        actionLabel = "Grant access",
                        onAction = { permissionLauncher.launch(viewModel.requiredPermissions) }
                    )
                }
                HealthPermissionStatus.GRANTED -> {
                    // Window selector
                    WindowChips(
                        selected = uiState.selectedWindowDays,
                        onSelect = viewModel::setWindow
                    )

                    if (uiState.isLoading) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val snap = uiState.snapshot
                        if (snap == null) {
                            Text("No data yet", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            SleepCard(snap.sleep)
                            NutritionCard(snap.nutrition)
                            WeightCard(snap.weight)
                            StepsCard(snap.steps)
                            ContextCard(snap)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// --- Window chips ---

@Composable
private fun WindowChips(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(2 to "2D", 7 to "7D", 14 to "14D", 30 to "30D", 90 to "90D")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (days, label) ->
            FilterChip(
                selected = selected == days,
                onClick = { onSelect(days) },
                label = { Text(label) }
            )
        }
    }
}

// --- Info / permission cards ---

@Composable
private fun HealthInfoCard(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

// --- Sleep ---

@Composable
private fun SleepCard(data: List<DailySleepData>) {
    HealthCard(title = "Sleep", unit = "hours") {
        if (data.isEmpty()) {
            EmptyData("No sleep data in this window")
        } else {
            val avg = data.map { it.durationHours }.average()
            StatRow(
                main = "${"%.1f".format(data.last().durationHours)}h last night",
                secondary = "avg ${"%.1f".format(avg)}h"
            )
            Spacer(Modifier.height(8.dp))
            SleepBarChart(data = data, modifier = Modifier.fillMaxWidth().height(100.dp))
            if (data.last().deepHours > 0.05 || data.last().remHours > 0.05) {
                Spacer(Modifier.height(4.dp))
                SleepLegend()
            }
        }
    }
}

@Composable
private fun SleepBarChart(data: List<DailySleepData>, modifier: Modifier) {
    val deepColor = MaterialTheme.colorScheme.primary
    val remColor = MaterialTheme.colorScheme.secondary
    val lightColor = MaterialTheme.colorScheme.surfaceVariant
    val maxH = data.maxOf { it.durationHours }.takeIf { it > 0.0 } ?: 8.0
    val scrollState = rememberScrollState()
    val barWidthDp = 20.dp
    val gapDp = 4.dp

    Row(
        modifier = modifier.horizontalScroll(scrollState),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(gapDp)
    ) {
        data.forEach { day ->
            val deepFrac = (day.deepHours / maxH).toFloat().coerceIn(0f, 1f)
            val remFrac = (day.remHours / maxH).toFloat().coerceIn(0f, 1f)
            val totalFrac = (day.durationHours / maxH).toFloat().coerceIn(0f, 1f)

            Column(
                modifier = Modifier.width(barWidthDp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val totalBarH = totalFrac * h
                        val deepH = deepFrac * h
                        val remH = remFrac * h

                        // light / base
                        drawRect(
                            color = lightColor,
                            topLeft = Offset(0f, h - totalBarH),
                            size = Size(w, totalBarH)
                        )
                        // REM on top of light
                        if (remH > 0) drawRect(
                            color = remColor,
                            topLeft = Offset(0f, h - remH - deepH),
                            size = Size(w, remH)
                        )
                        // deep on top
                        if (deepH > 0) drawRect(
                            color = deepColor,
                            topLeft = Offset(0f, h - deepH),
                            size = Size(w, deepH)
                        )
                    }
                }
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SleepLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem("Deep", MaterialTheme.colorScheme.primary)
        LegendItem("REM", MaterialTheme.colorScheme.secondary)
        LegendItem("Light", MaterialTheme.colorScheme.surfaceVariant)
    }
}

// --- Nutrition ---

@Composable
private fun NutritionCard(data: List<DailyNutritionData>) {
    HealthCard(title = "Nutrition", unit = "kcal") {
        if (data.isEmpty()) {
            EmptyData("No nutrition data in this window — make sure MyFitnessPal is syncing to Health Connect")
        } else {
            val last = data.last()
            val avg = data.map { it.calories }.average()
            StatRow(
                main = "${last.calories.toInt()}kcal today",
                secondary = "avg ${avg.toInt()}kcal"
            )
            last.lastMealTime?.let { t ->
                val h = ((System.currentTimeMillis() - t.toEpochMilli()) / 3_600_000).toInt()
                if (h in 1..24) Text("Last meal $h hours ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            SimpleBarChart(
                values = data.map { it.date.dayOfMonth.toString() to it.calories },
                barColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )
            if (last.proteinGrams > 0 || last.carbsGrams > 0 || last.fatGrams > 0) {
                Spacer(Modifier.height(8.dp))
                MacroBar(protein = last.proteinGrams, carbs = last.carbsGrams, fat = last.fatGrams)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendItem("Protein ${last.proteinGrams.toInt()}g", MaterialTheme.colorScheme.primary)
                    LegendItem("Carbs ${last.carbsGrams.toInt()}g", MaterialTheme.colorScheme.tertiary)
                    LegendItem("Fat ${last.fatGrams.toInt()}g", MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

@Composable
private fun MacroBar(protein: Double, carbs: Double, fat: Double) {
    val total = (protein + carbs + fat).takeIf { it > 0.0 } ?: 1.0
    val proteinFrac = (protein / total).toFloat()
    val carbsFrac = (carbs / total).toFloat()
    val proteinColor = MaterialTheme.colorScheme.primary
    val carbsColor = MaterialTheme.colorScheme.tertiary
    val fatColor = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
        val w = size.width
        val h = size.height
        val pW = w * proteinFrac
        val cW = w * carbsFrac
        val fW = w - pW - cW

        drawRect(proteinColor, Offset(0f, 0f), Size(pW, h))
        drawRect(carbsColor, Offset(pW, 0f), Size(cW, h))
        drawRect(fatColor, Offset(pW + cW, 0f), Size(fW, h))
    }
}

// --- Weight ---

@Composable
private fun WeightCard(data: List<DailyWeightData>) {
    HealthCard(title = "Weight", unit = "kg") {
        if (data.isEmpty()) {
            EmptyData("No weight data in this window — make sure your Withings scale is syncing")
        } else {
            val last = data.last()
            val change = if (data.size > 1) last.weightKg - data.first().weightKg else 0.0
            val changeStr = when {
                change > 0.05 -> "+${"%.1f".format(change)}kg"
                change < -0.05 -> "${"%.1f".format(change)}kg"
                else -> "stable"
            }
            StatRow(
                main = "${"%.1f".format(last.weightKg)}kg",
                secondary = "$changeStr in window"
            )
            Spacer(Modifier.height(8.dp))
            SparklineChart(
                values = data.map { it.weightKg },
                labels = data.map { it.date.dayOfMonth.toString() },
                modifier = Modifier.fillMaxWidth().height(80.dp),
                lineColor = MaterialTheme.colorScheme.primary,
                fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
        }
    }
}

// --- Steps ---

@Composable
private fun StepsCard(data: List<DailyStepsData>) {
    HealthCard(title = "Steps", unit = "steps/day") {
        if (data.isEmpty()) {
            EmptyData("No step data in this window")
        } else {
            val last = data.last()
            val avg = data.map { it.steps }.average().toLong()
            StatRow(
                main = "${last.steps} yesterday",
                secondary = "avg $avg/day"
            )
            Spacer(Modifier.height(8.dp))
            SimpleBarChart(
                values = data.map { it.date.dayOfMonth.toString() to it.steps.toDouble() },
                barColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )
        }
    }
}

// --- "What Carl's Brain sees" card ---

@Composable
private fun ContextCard(snapshot: HealthSnapshot) {
    var expanded by remember { mutableStateOf(false) }
    val ctx = snapshot.toContextString()
    if (ctx.isBlank()) return

    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("What Carl's Brain sees", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium)
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(16.dp))
                }
            }
            AnimatedVisibility(visible = expanded) {
                Text(ctx,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

// --- Shared chart primitives ---

@Composable
private fun SparklineChart(
    values: List<Double>,
    labels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    lineColor: Color,
    fillColor: Color
) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0

    // Use Canvas directly with the caller's modifier (fillMaxWidth + fixed height).
    // Wrapping in horizontalScroll gives the Canvas an unbounded parent width,
    // which causes fillMaxSize() to resolve to ~0, collapsing all points to x=0.
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val step = w / (values.size - 1)
        val path = Path(); val fill = Path()

        values.forEachIndexed { i, v ->
            val x = i * step
            val y = h - ((v - min) / range * h).toFloat()
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo((values.size - 1) * step, h); fill.close()

        drawPath(fill, fillColor)
        drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun SimpleBarChart(
    values: List<Pair<String, Double>>,
    barColor: Color,
    modifier: Modifier
) {
    if (values.isEmpty()) return
    val max = values.maxOf { it.second }.takeIf { it > 0.0 } ?: 1.0
    val scrollState = rememberScrollState()
    val barW = 20.dp; val gap = 4.dp

    Row(
        modifier = modifier.horizontalScroll(scrollState),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(gap)
    ) {
        values.forEach { (label, value) ->
            val frac = (value / max).toFloat().coerceIn(0f, 1f)
            Column(
                modifier = Modifier.width(barW),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Canvas(Modifier.fillMaxSize()) {
                        val barH = size.height * frac
                        drawRect(barColor, Offset(0f, size.height - barH), Size(size.width, barH))
                    }
                }
                Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- Shared layout helpers ---

@Composable
private fun HealthCard(title: String, unit: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(unit, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun StatRow(main: String, secondary: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(main, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(secondary, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyData(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
