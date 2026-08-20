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
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.action.clickable
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.appwidget.action.ActionCallback
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.local.worker.AmbientBufferService
import com.carlmanning.carlsbrain.ui.VoiceCaptureActivity

class QuickCaptureWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                QuickCaptureContent(context)
            }
        }
    }
}

@Composable
private fun QuickCaptureContent(context: Context) {
    val voiceIntent = Intent(context, VoiceCaptureActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val todoIntent = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_OPEN_CAPTURE_TODO
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val meetingIntent = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_START_MEETING
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.primaryContainer)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Carl's Brain",
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = GlanceModifier.padding(start = 4.dp, bottom = 2.dp)
        )
        // Four buttons in a 2x2 grid. Note deliberately is not one of them: it opened the
        // same Quick Capture screen as To-Do with only the type preselected, and the type is
        // switchable once you are there — so it cost a whole cell to save one tap on the
        // rarer path. Five buttons in a 2x2 widget would mean ~43dp touch targets, under the
        // 48dp minimum.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WidgetButton(emoji = "🎤", label = "Voice", onClick = actionStartActivity(voiceIntent))
            WidgetButton(emoji = "✅", label = "To-Do", onClick = actionStartActivity(todoIntent))
        }
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WidgetButton(emoji = "🎙️", label = "Meeting", onClick = actionStartActivity(meetingIntent))
            // Unlike the others this opens nothing — it messages the service directly, because
            // the value of the buffer is that it can be saved without waiting for a cold start.
            WidgetButton(
                emoji = "⏺️",
                label = "Save that",
                onClick = actionRunCallback<ToggleBufferRecordingAction>()
            )
        }
    }
}

@Composable
private fun RowScope.WidgetButton(emoji: String, label: String, onClick: Action) {
    Column(
        modifier = GlanceModifier
            .defaultWeight()
            .clickable(onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = emoji,
            style = TextStyle(fontSize = 32.sp, textAlign = TextAlign.Center)
        )
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        )
    }
}

/**
 * Starts a buffer-backed recording, or stops one already running.
 *
 * Deliberately a service message rather than an activity launch: the biometric gate and the
 * app's cold start both stand between a tap and a recording, and the seconds that costs are
 * exactly the ones Carl is trying to keep.
 */
class ToggleBufferRecordingAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        AmbientBufferService.send(context, AmbientBufferService.ACTION_TOGGLE)
    }
}

class QuickCaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickCaptureWidget()
}
