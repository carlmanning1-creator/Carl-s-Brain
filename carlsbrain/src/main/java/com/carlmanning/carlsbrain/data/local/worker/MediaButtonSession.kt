package com.carlmanning.carlsbrain.data.local.worker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.carlmanning.carlsbrain.MainActivity

/**
 * Headset / Bluetooth media-button path into voice capture.
 *
 * Uses the **platform** [android.media.session.MediaSession] rather than
 * androidx.media3.session — media3 is not a dependency of this module (see
 * carlsbrain/build.gradle.kts) and a hands-free trigger does not justify pulling one in.
 *
 * Good-citizen rules baked in here:
 *  - the session is only alive while a Carl's Brain component is alive (MainActivity in
 *    the foreground, or VoiceCaptureService if the user still has the wake word on).
 *    Nothing runs in the background, so this costs no battery.
 *  - we never request audio focus and never publish STATE_PLAYING, so we do not try to
 *    wrestle the button away from a media app that is actually playing.
 *
 * Known limitation: Android routes media buttons to the app that most recently held an
 * active session and played audio. If Spotify (or any player) has been playing, Spotify
 * owns the button until it goes away. This path is reliable when nothing else is playing.
 */
object MediaButtonSession {

    private const val TAG = "MediaButtonSession"
    private const val SESSION_TAG = "CarlsBrainCapture"
    private const val REQUEST_CODE = 4102

    /**
     * Media buttons arrive as ACTION_DOWN *and* ACTION_UP, and Bluetooth stacks like to
     * repeat. Collapse anything inside this window into a single capture.
     */
    private const val DEBOUNCE_MS = 1_000L

    @Volatile
    private var lastTriggerMs = 0L

    private var session: MediaSession? = null

    /**
     * Number of live owners. Kept so an activity recreation (rotation) that attaches
     * before the outgoing instance's onDestroy can't leave us session-less.
     */
    private var owners = 0

    /** Creates (or keeps) the session. Safe to call repeatedly. */
    @Synchronized
    fun attach(context: Context) {
        owners++
        if (session != null) return
        val appContext = context.applicationContext

        val created = runCatching {
            MediaSession(appContext, SESSION_TAG).apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        return handleMediaButtonIntent(appContext, mediaButtonIntent) ||
                                super.onMediaButtonEvent(mediaButtonIntent)
                    }
                })

                // A session with no playback state is not a valid button target. We publish
                // PAUSED (never PLAYING) — enough to be routed the button when no one else
                // is playing, not enough to look like we're competing for playback.
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)
                        .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                        .build()
                )

                // Fallback target for when this session is gone (app closed): the framework
                // delivers ACTION_MEDIA_BUTTON to the declared receiver instead.
                val receiver = ComponentName(appContext, MediaButtonReceiver::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setMediaButtonBroadcastReceiver(receiver)
                } else {
                    @Suppress("DEPRECATION")
                    setMediaButtonReceiver(
                        PendingIntent.getBroadcast(
                            appContext,
                            REQUEST_CODE,
                            Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                }

                isActive = true
            }
        }.getOrElse {
            Log.w(TAG, "Could not create media session", it)
            null
        }

        session = created
    }

    /** Releases the session once the last owner is gone. Safe to call when unattached. */
    @Synchronized
    fun release() {
        if (owners > 0) owners--
        if (owners > 0) return
        runCatching {
            session?.isActive = false
            session?.release()
        }.onFailure { Log.w(TAG, "Could not release media session", it) }
        session = null
    }

    /**
     * @return true if the event was a capture trigger we consumed.
     */
    fun handleMediaButtonIntent(context: Context, intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return false

        val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        }
        if (event == null) return false

        val isCaptureKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
        if (!isCaptureKey) return false

        // Only ACTION_DOWN, only the first of a repeat, and only once per debounce window —
        // a single physical press otherwise fires DOWN + UP.
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0) return true

        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastTriggerMs < DEBOUNCE_MS) return true
            lastTriggerMs = now
        }

        launchVoiceCapture(context)
        return true
    }

    /**
     * Same deep link the Quick Settings tile and the widget use:
     * MainActivity routes ACTION_OPEN_CAPTURE_VOICE to
     * AppViewModel.requestCapture(type = "TODO", startVoice = true).
     */
    private fun launchVoiceCapture(context: Context) {
        val intent = Intent(context.applicationContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CAPTURE_VOICE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { context.applicationContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not start voice capture from media button", it) }
    }
}

/**
 * Receives ACTION_MEDIA_BUTTON when no Carl's Brain [MediaSession] is alive (i.e. the app
 * is closed). Declared in the manifest and registered on the session via
 * setMediaButtonBroadcastReceiver, so the process only wakes on an actual button press.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MediaButtonSession.handleMediaButtonIntent(context, intent)
    }
}
