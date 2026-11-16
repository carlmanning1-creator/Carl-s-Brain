package com.carlmanning.carlsbrain.ui.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.domain.DueDate
import com.carlmanning.carlsbrain.domain.capture.SaveCapture
import com.carlmanning.carlsbrain.ui.theme.CarlsBrainTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The widget's To-do and Note buttons. A write-only capture box that deliberately skips the
 * app's biometric gate — safely, because it can only add:
 *
 * - It shows **no existing data**: no lists, no recent items, no titles. The only thing read from
 *   the database is the bucket names for the picker, and those are non-vault only.
 * - "Auto" and the picker both draw from non-vault buckets, and the same list is all Claude is
 *   offered when auto-tagging, so nothing typed here can land in the vault.
 * - It is **not** shown over the lock screen (no showWhenLocked / turnScreenOn): the phone's own
 *   lock still applies. It runs in its own task, so finishing it never reveals MainActivity.
 *
 * Saving goes through [SaveCapture], the same path as the Quick Capture screen, and the slow half
 * of that runs on appScope, so finishing this activity cannot cancel it.
 */
class QuickAddActivity : ComponentActivity() {

    companion object {
        const val ACTION_TODO = "com.carlmanning.carlsbrain.QUICK_ADD_TODO"
        const val ACTION_NOTE = "com.carlmanning.carlsbrain.QUICK_ADD_NOTE"
        const val EXTRA_TYPE = "quick_add_type"
        const val TYPE_TODO = "todo"
        const val TYPE_NOTE = "note"

        /**
         * Distinct actions as well as the extra: two PendingIntents to the same component that
         * differ only in extras are the same PendingIntent, and the second button would open
         * the first one's type.
         */
        fun intent(context: Context, note: Boolean): Intent =
            Intent(context, QuickAddActivity::class.java).apply {
                action = if (note) ACTION_NOTE else ACTION_TODO
                putExtra(EXTRA_TYPE, if (note) TYPE_NOTE else TYPE_TODO)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startAsNote = intent?.getStringExtra(EXTRA_TYPE) == TYPE_NOTE ||
            intent?.action == ACTION_NOTE
        setContent {
            CarlsBrainTheme {
                QuickAddContent(
                    startAsNote = startAsNote,
                    onCancel = { finish() },
                    onSave = { request -> save(request) }
                )
            }
        }
    }

    private var saving = false

    private fun save(request: SaveCapture.Request) {
        if (saving) return
        saving = true
        val appContext = applicationContext
        // appScope, not lifecycleScope: finish() below must not be able to cancel the insert.
        CarlsBrainApp.appScope.launch {
            val result = try {
                val buckets = AppDatabase.getInstance(appContext).bucketDao()
                    .getNonVaultBuckets().first()
                    .filter { !it.isVault } // belt and braces: the vault never reaches this path
                // A chosen bucket must still be one of these — never trust it into the vault.
                val safe = request.copy(
                    selectedBucketId = request.selectedBucketId?.takeIf { id -> buckets.any { it.id == id } }
                )
                SaveCapture.save(appContext, safe, buckets)
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                val message = when (result) {
                    is SaveCapture.Result.Saved -> "Saved to ${result.bucketName}"
                    SaveCapture.Result.Blank -> "Nothing to save"
                    SaveCapture.Result.NoBucket -> "No bucket to save into — open the app to create one"
                    null -> "Couldn’t save — try again"
                }
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                if (result is SaveCapture.Result.Saved) {
                    if (!isFinishing && !isDestroyed) finish()
                } else {
                    // Leave the text where it is so nothing typed is lost.
                    saving = false
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddContent(
    startAsNote: Boolean,
    onCancel: () -> Unit,
    onSave: (SaveCapture.Request) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Non-vault only, and names are all that is read.
    val buckets: List<BucketEntity> by remember {
        AppDatabase.getInstance(context).bucketDao().getNonVaultBuckets()
    }.collectAsState(initial = emptyList())
    val visibleBuckets = buckets.filter { !it.isVault }

    var isNote by rememberSaveable { mutableStateOf(startAsNote) }
    var text by rememberSaveable { mutableStateOf("") }
    var bucketId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dueDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var bucketMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDateMs by remember { mutableStateOf<Long?>(null) }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dueDate)
    val pickerTime = DueDate.pickerTime(dueDate)
    val timeState = rememberTimePickerState(
        initialHour = pickerTime.first,
        initialMinute = pickerTime.second,
        is24Hour = true
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingDateMs = datePickerState.selectedDateMillis
                    showDatePicker = false
                    showTimePicker = pendingDateMs != null
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = datePickerState) }
    }

    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Set time (optional)") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    dueDate = pendingDateMs?.let { DueDate.atTimeFromPicker(it, timeState.hour, timeState.minute) }
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Date only means the end of that day, as in Quick Capture.
                    dueDate = pendingDateMs?.let { DueDate.endOfDayFromPicker(it) }
                    showTimePicker = false
                }) { Text("No time") }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // A stray tap outside must not throw away something half-typed.
                onClick = { if (text.isBlank()) onCancel() }
            )
            .imePadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier
                .fillMaxWidth()
                // Swallow taps so tapping the card does not dismiss it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isNote, onClick = { isNote = false }, label = { Text("To-do") })
                    FilterChip(selected = isNote, onClick = { isNote = true }, label = { Text("Note") })
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(if (isNote) "Note…" else "What needs doing?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                    minLines = if (isNote) 3 else 1,
                    maxLines = 8
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        val label = visibleBuckets.find { it.id == bucketId }?.name ?: "Auto"
                        OutlinedButton(onClick = { bucketMenu = true }) { Text("Bucket: $label") }
                        DropdownMenu(expanded = bucketMenu, onDismissRequest = { bucketMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Auto") },
                                onClick = { bucketId = null; bucketMenu = false }
                            )
                            visibleBuckets.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text(b.name) },
                                    onClick = { bucketId = b.id; bucketMenu = false }
                                )
                            }
                        }
                    }
                    if (!isNote) {
                        Spacer(Modifier.width(8.dp))
                        val due = dueDate
                        OutlinedButton(onClick = { showDatePicker = true }) {
                            Text(if (due == null) "Due date" else formatDue(due))
                        }
                        if (due != null) {
                            TextButton(onClick = { dueDate = null }) { Text("Clear") }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = text.isNotBlank(),
                        onClick = {
                            // The activity guards against a double tap.
                            onSave(
                                SaveCapture.Request(
                                    isNote = isNote,
                                    text = text,
                                    selectedBucketId = bucketId,
                                    dueDate = if (isNote) null else dueDate
                                )
                            )
                        }
                    ) { Text("Save") }
                }
                Text(
                    "Adds only — open the app to see your lists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDue(ms: Long): String {
    val pattern = if (DueDate.hasExplicitTime(ms)) "EEE d MMM, HH:mm" else "EEE d MMM"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
