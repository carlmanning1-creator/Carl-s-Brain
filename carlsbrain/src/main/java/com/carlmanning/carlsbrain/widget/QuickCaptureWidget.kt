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
import androidx.glance.appwidget.action.actionStartActivity
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
import com.carlmanning.carlsbrain.MainActivity
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
    val noteIntent = Intent(context, MainActivity::class.java).apply {
        action = MainActivity.ACTION_OPEN_CAPTURE_NOTE
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Carl's Brain",
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = GlanceModifier.padding(bottom = 4.dp)
        )
        // Row 1: Voice + Note
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WidgetButton(emoji = "🎤", label = "Voice", intent = voiceIntent)
            WidgetButton(emoji = "📝", label = "Note", intent = noteIntent)
        }
        // Row 2: To-Do + Meeting
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WidgetButton(emoji = "✅", label = "To-Do", intent = todoIntent)
            WidgetButton(emoji = "🎙️", label = "Meeting", intent = meetingIntent)
        }
    }
}

@Composable
private fun RowScope.WidgetButton(emoji: String, label: String, intent: Intent) {
    Column(
        modifier = GlanceModifier
            .defaultWeight()
            .clickable(actionStartActivity(intent))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = emoji,
            style = TextStyle(fontSize = 18.sp, textAlign = TextAlign.Center)
        )
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        )
    }
}

class QuickCaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickCaptureWidget()
}
