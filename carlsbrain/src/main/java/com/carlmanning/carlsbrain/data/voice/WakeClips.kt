package com.carlmanning.carlsbrain.data.voice

import android.content.Context
import java.io.File

/**
 * The last few seconds of audio behind each wake-word trigger, so a false activation can be
 * *listened to* rather than guessed at. The activation log says when and how loud; it cannot say
 * whether it was the television.
 *
 * Kept deliberately small and local:
 *  - the newest [MAX_CLIPS] only, about two seconds each;
 *  - in filesDir, never synced to Drive, never sent anywhere, never in the error log;
 *  - cleared with the activation log, and when the wake word is switched off.
 *
 * This is Carl's explicit choice (November 2026). It records only what the wake word was
 * already listening to at the moment it fired — nothing here arms a microphone.
 */
object WakeClips {
    private const val DIR = "wake_clips"
    const val MAX_CLIPS = 20

    const val OUTCOME_SPEECH = "speech"
    const val OUTCOME_NOTHING = "nothing"

    /** @param outcome one of the OUTCOME_ constants, or blank when it was never determined. */
    data class Clip(val file: File, val at: Long, val rms: Int, val outcome: String)

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** Writes the clip and prunes the oldest. Returns null on any failure — never throws. */
    fun save(context: Context, pcm: ShortArray, rms: Int, at: Long = System.currentTimeMillis()): File? =
        runCatching {
            val d = dir(context)
            if (!d.exists()) d.mkdirs()
            val file = File(d, "${at}_${rms.coerceAtLeast(0)}.wav")
            file.writeBytes(Wav.encode(pcm, CommandCapture.SAMPLE_RATE))
            d.listFiles { f -> f.name.endsWith(".wav") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_CLIPS)
                ?.forEach { it.delete() }
            file
        }.getOrNull()

    /** Records what followed the trigger by renaming the file. Safe if the file has gone. */
    fun markOutcome(file: File?, outcome: String) {
        if (file == null || !file.exists()) return
        runCatching {
            val base = file.name.removeSuffix(".wav")
            // Already marked — a clip gets one outcome.
            if (base.count { it == '_' } >= 2) return
            file.renameTo(File(file.parentFile, "${base}_$outcome.wav"))
        }
    }

    fun list(context: Context): List<Clip> =
        runCatching {
            dir(context).listFiles { f -> f.name.endsWith(".wav") }
                ?.mapNotNull { f ->
                    val parts = f.name.removeSuffix(".wav").split('_')
                    val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    Clip(f, at, parts.getOrNull(1)?.toIntOrNull() ?: -1, parts.getOrNull(2).orEmpty())
                }
                ?.sortedByDescending { it.at }
                ?: emptyList()
        }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }
}
