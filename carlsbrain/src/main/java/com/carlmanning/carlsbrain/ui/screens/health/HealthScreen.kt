package com.carlmanning.carlsbrain.ui.screens.health

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.health.*
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.theme.nestedSurface
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HealthScreen(
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: HealthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Which manual-entry dialog is open (null = none).
    var entryMetric by remember { mutableStateOf<HealthMetric?>(null) }
    // True between tapping Save and the save finishing — keeps the dialog on screen
    // showing its spinner, then closes it once the write completes (success or not).
    var savePending by remember { mutableStateOf(false) }

    // Wait for isSaving to go true then false before closing, rather than keying on
    // isSaving directly — the flag may not have reached the composition yet on the
    // frame Save is tapped, which would close the dialog before the write finished.
    LaunchedEffect(savePending) {
        if (savePending) {
            withTimeoutOrNull(1_000L) { snapshotFlow { isSaving }.first { it } }
            snapshotFlow { isSaving }.first { !it }
            savePending = false
            entryMetric = null
        }
    }

    LaunchedEffect(saveError) {
        saveError?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSaveError()
        }
    }

    entryMetric?.let { metric ->
        ManualEntryDialog(
            metric = metric,
            isSaving = isSaving,
            onDismiss = { savePending = false; entryMetric = null },
            onConfirm = { value ->
                when (metric) {
                    HealthMetric.WEIGHT -> viewModel.addWeight(value)
                    HealthMetric.NUTRITION -> viewModel.addCalories(value)
                    HealthMetric.STEPS -> viewModel.addSteps(value.toLong())
                    HealthMetric.SLEEP -> {
                        val now = System.currentTimeMillis()
                        viewModel.addSleep(now - (value * 3_600_000).toLong(), now)
                    }
                }
                savePending = true
            }
        )
    }

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
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                            SleepCard(snap.sleep, onAdd = { entryMetric = HealthMetric.SLEEP })
                            NutritionCard(snap.nutrition, onAdd = { entryMetric = HealthMetric.NUTRITION })
                            WeightCard(snap.weight, onAdd = { entryMetric = HealthMetric.WEIGHT })
                            StepsCard(snap.steps, onAdd = { entryMetric = HealthMetric.STEPS })
                            ContextCard(snap)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// --- Manual entry ---

private enum class HealthMetric(
    val dialogTitle: String,
    val fieldLabel: String,
    val decimal: Boolean,
    val note: String? = null
) {
    SLEEP("Add sleep", "Hours slept", true, "Recorded as a sleep session ending now."),
    NUTRITION("Add calories", "Calories (kcal)", true),
    WEIGHT("Add weight", "Weight (kg)", true),
    STEPS("Add steps", "Steps", false, "Recorded against the last hour.")
}

@Composable
private fun ManualEntryDialog(
    metric: HealthMetric,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var input by remember(metric) { mutableStateOf("") }
    val parsed = input.trim().toDoubleOrNull()
    val valid = parsed != null && parsed > 0.0 && !parsed.isNaN() && !parsed.isInfinite()

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(metric.dialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { new ->
                        // Keep it to digits (and one decimal point for decimal metrics).
                        input = new.filter { it.isDigit() || (metric.decimal && it == '.') }
                    },
                    label = { Text(metric.fieldLabel) },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (metric.decimal) KeyboardType.Decimal else KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                metric.note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                TextButton(
                    onClick = { parsed?.let(onConfirm) },
                    enabled = valid
                ) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
        }
    )
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
private fun SleepCard(data: List<DailySleepData>, onAdd: () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    var selectedDay by remember { mutableStateOf<DailySleepData?>(null) }

    HealthCard(title = "Sleep", unit = "hours", expanded = expanded,
        onToggle = { expanded = !expanded }, onAdd = onAdd) {
        if (data.isEmpty()) {
            EmptyData("No sleep data in this window")
        } else {
            val avg = data.map { it.durationHours }.average()
            StatRow(
                main = "${"%.1f".format(data.last().durationHours)}h last night",
                secondary = "avg ${"%.1f".format(avg)}h"
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Spacer(Modifier.height(4.dp))
                    SleepBarChart(
                        data = data,
                        selectedDate = selectedDay?.date,
                        onBarClick = { day -> selectedDay = if (selectedDay?.date == day.date) null else day },
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    )
                    if (data.last().deepHours > 0.05 || data.last().remHours > 0.05) {
                        SleepLegend()
                    }
                    selectedDay?.let { day ->
                        Spacer(Modifier.height(4.dp))
                        SleepDetailPanel(day)
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepBarChart(
    data: List<DailySleepData>,
    selectedDate: LocalDate? = null,
    onBarClick: (DailySleepData) -> Unit = {},
    modifier: Modifier
) {
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
            val isSelected = selectedDate == day.date
            val alpha = if (selectedDate != null && !isSelected) 0.3f else 1.0f

            val deepFrac = (day.deepHours / maxH).toFloat().coerceIn(0f, 1f)
            val remFrac = (day.remHours / maxH).toFloat().coerceIn(0f, 1f)
            val totalFrac = (day.durationHours / maxH).toFloat().coerceIn(0f, 1f)

            Column(
                modifier = Modifier
                    .width(barWidthDp)
                    .clickable { onBarClick(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val totalBarH = totalFrac * h
                        val deepH = deepFrac * h
                        val remH = remFrac * h

                        drawRect(
                            color = lightColor.copy(alpha = alpha),
                            topLeft = Offset(0f, h - totalBarH),
                            size = Size(w, totalBarH)
                        )
                        if (remH > 0) drawRect(
                            color = remColor.copy(alpha = alpha),
                            topLeft = Offset(0f, h - remH - deepH),
                            size = Size(w, remH)
                        )
                        if (deepH > 0) drawRect(
                            color = deepColor.copy(alpha = alpha),
                            topLeft = Offset(0f, h - deepH),
                            size = Size(w, deepH)
                        )
                        if (isSelected && totalBarH > 0) {
                            drawRect(
                                color = Color.White.copy(alpha = 0.15f),
                                topLeft = Offset(0f, h - totalBarH),
                                size = Size(w, totalBarH)
                            )
                        }
                    }
                }
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SleepDetailPanel(entry: DailySleepData) {
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    val total = entry.durationHours.takeIf { it > 0.0 } ?: 1.0

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = nestedSurface(1))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.date.format(fmt),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold)
            Text("${"%.1f".format(entry.durationHours)}h total sleep",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.deepHours > 0.01) SleepStageRow(
                label = "Deep",
                hours = entry.deepHours,
                pct = (entry.deepHours / total * 100).toInt(),
                fraction = (entry.deepHours / total).toFloat(),
                color = MaterialTheme.colorScheme.primary
            )
            if (entry.remHours > 0.01) SleepStageRow(
                label = "REM",
                hours = entry.remHours,
                pct = (entry.remHours / total * 100).toInt(),
                fraction = (entry.remHours / total).toFloat(),
                color = MaterialTheme.colorScheme.secondary
            )
            if (entry.lightHours > 0.01) SleepStageRow(
                label = "Light",
                hours = entry.lightHours,
                pct = (entry.lightHours / total * 100).toInt(),
                fraction = (entry.lightHours / total).toFloat(),
                color = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun SleepStageRow(label: String, hours: Double, pct: Int, fraction: Float, color: Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("${"%.1f".format(hours)}h  $pct%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            drawRect(trackColor, size = size)
            drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
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
private fun NutritionCard(data: List<DailyNutritionData>, onAdd: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    HealthCard(title = "Nutrition", unit = "kcal", expanded = expanded,
        onToggle = { expanded = !expanded }, onAdd = onAdd) {
        if (data.isEmpty()) {
            EmptyData("No nutrition data in this window — make sure MyFitnessPal is syncing to Health Connect")
        } else {
            val last = data.last()
            val avg = data.map { it.calories }.average()
            StatRow(
                main = "${last.calories.toInt()}kcal today",
                secondary = "avg ${avg.toInt()}kcal"
            )
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    last.lastMealTime?.let { t ->
                        val h = ((System.currentTimeMillis() - t.toEpochMilli()) / 3_600_000).toInt()
                        if (h in 1..24) Text("Last meal $h hours ago",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    SimpleBarChart(
                        values = data.map { it.date.dayOfMonth.toString() to it.calories },
                        barColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        selectedIndex = selectedIndex,
                        onBarClick = { idx -> selectedIndex = if (selectedIndex == idx) null else idx }
                    )
                    if (last.proteinGrams > 0 || last.carbsGrams > 0 || last.fatGrams > 0) {
                        Spacer(Modifier.height(4.dp))
                        MacroBar(protein = last.proteinGrams, carbs = last.carbsGrams, fat = last.fatGrams)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LegendItem("Protein ${last.proteinGrams.toInt()}g", MaterialTheme.colorScheme.primary)
                            LegendItem("Carbs ${last.carbsGrams.toInt()}g", MaterialTheme.colorScheme.tertiary)
                            LegendItem("Fat ${last.fatGrams.toInt()}g", MaterialTheme.colorScheme.secondary)
                        }
                    }
                    selectedIndex?.let { idx ->
                        if (idx < data.size) {
                            Spacer(Modifier.height(4.dp))
                            NutritionDetailPanel(data[idx])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NutritionDetailPanel(entry: DailyNutritionData) {
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    val totalGrams = (entry.proteinGrams + entry.carbsGrams + entry.fatGrams).takeIf { it > 0.0 } ?: 1.0

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = nestedSurface(1))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.date.format(fmt),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold)
            Text("${entry.calories.toInt()} kcal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.proteinGrams > 0) MacroRow(
                label = "Protein",
                grams = entry.proteinGrams,
                pct = (entry.proteinGrams / totalGrams * 100).toInt(),
                fraction = (entry.proteinGrams / totalGrams).toFloat(),
                color = MaterialTheme.colorScheme.primary
            )
            if (entry.carbsGrams > 0) MacroRow(
                label = "Carbs",
                grams = entry.carbsGrams,
                pct = (entry.carbsGrams / totalGrams * 100).toInt(),
                fraction = (entry.carbsGrams / totalGrams).toFloat(),
                color = MaterialTheme.colorScheme.tertiary
            )
            if (entry.fatGrams > 0) MacroRow(
                label = "Fat",
                grams = entry.fatGrams,
                pct = (entry.fatGrams / totalGrams * 100).toInt(),
                fraction = (entry.fatGrams / totalGrams).toFloat(),
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun MacroRow(label: String, grams: Double, pct: Int, fraction: Float, color: Color) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text("${grams.toInt()}g  $pct%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            drawRect(trackColor, size = size)
            drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
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
private fun WeightCard(data: List<DailyWeightData>, onAdd: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    HealthCard(title = "Weight", unit = "kg", expanded = expanded,
        onToggle = { expanded = !expanded }, onAdd = onAdd) {
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
            AnimatedVisibility(visible = expanded) {
                val minW = data.minOf { it.weightKg }
                val maxW = data.maxOf { it.weightKg }
                val avgW = data.map { it.weightKg }.average()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Spacer(Modifier.height(4.dp))
                    SparklineChart(
                        values = data.map { it.weightKg },
                        labels = data.map { it.date.dayOfMonth.toString() },
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        lineColor = MaterialTheme.colorScheme.primary,
                        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        avgValue = avgW
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        WeightStat("Min", "${"%.1f".format(minW)}kg")
                        WeightStat("Avg", "${"%.1f".format(avgW)}kg")
                        WeightStat("Max", "${"%.1f".format(maxW)}kg")
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Steps ---

@Composable
private fun StepsCard(data: List<DailyStepsData>, onAdd: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    HealthCard(title = "Steps", unit = "steps/day", expanded = expanded,
        onToggle = { expanded = !expanded }, onAdd = onAdd) {
        val hasRealData = data.any { it.steps > 0L }
        when {
            data.isEmpty() || !hasRealData -> {
                EmptyData(
                    if (data.isEmpty()) "No step data in this window"
                    else "No steps synced to Health Connect for this window — open Garmin Connect and let it refresh, then come back here"
                )
            }
            else -> {
                val daysWithData = data.filter { it.steps > 0L }
                val last = daysWithData.last()
                val avg = daysWithData.map { it.steps }.average().toLong()
                StatRow(
                    main = "${last.steps} on ${last.date.dayOfMonth}/${last.date.monthValue}",
                    secondary = "avg $avg/day"
                )
                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spacer(Modifier.height(4.dp))
                        SimpleBarChart(
                            values = data.map { it.date.dayOfMonth.toString() to it.steps.toDouble() },
                            barColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().height(80.dp)
                        )
                    }
                }
            }
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
    fillColor: Color,
    avgValue: Double? = null
) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    val avgLineColor = lineColor.copy(alpha = 0.5f)

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

        avgValue?.let { avg ->
            val avgY = (h - ((avg - min) / range * h)).toFloat()
            drawLine(
                color = avgLineColor,
                start = Offset(0f, avgY),
                end = Offset(w, avgY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f), 0f)
            )
        }
    }
}

@Composable
private fun SimpleBarChart(
    values: List<Pair<String, Double>>,
    barColor: Color,
    modifier: Modifier,
    selectedIndex: Int? = null,
    onBarClick: ((Int) -> Unit)? = null
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
        values.forEachIndexed { idx, (label, value) ->
            val frac = (value / max).toFloat().coerceIn(0f, 1f)
            val isSelected = selectedIndex == idx
            val alpha = if (selectedIndex != null && !isSelected) 0.3f else 1.0f
            Column(
                modifier = Modifier
                    .width(barW)
                    .then(if (onBarClick != null) Modifier.clickable { onBarClick(idx) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Canvas(Modifier.fillMaxSize()) {
                        val barH = size.height * frac
                        if (barH > 0) {
                            drawRect(barColor.copy(alpha = alpha), Offset(0f, size.height - barH), Size(size.width, barH))
                            if (isSelected) {
                                drawRect(Color.White.copy(alpha = 0.2f), Offset(0f, size.height - barH), Size(size.width, barH))
                            }
                        }
                    }
                }
                Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// --- Shared layout helpers ---

@Composable
private fun HealthCard(
    title: String,
    unit: String,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onToggle != null) Modifier.clickable { onToggle() } else Modifier),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(unit, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onAdd != null) {
                        // Own IconButton so the tap is consumed here and never reaches
                        // the header's clickable — adding must not toggle the accordion.
                        IconButton(onClick = onAdd, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add $title entry",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (onToggle != null) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
