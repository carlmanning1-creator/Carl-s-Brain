package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import java.io.File

/**
 * Where a meeting's audio lives on the device before (and after) it reaches Drive.
 *
 * ## Why not cacheDir
 * Recordings used to be written to `cacheDir`. Android is free to clear that directory at any
 * time under storage pressure — including while a meeting recorded offline is still queued for
 * upload. The result would be a meeting row pointing at a file that no longer exists, with the
 * audio gone for good. The audio is the one part of a meeting that cannot be reconstructed
 * later, so it belongs somewhere the system will not reclaim behind our back.
 *
 * The rolling ambient buffer stays in `cacheDir` deliberately: it is genuinely transient, it is
 * large (up to 38 MB), and losing it costs nothing that was ever promised to Carl.
 *
 * ## The trade-off this creates
 * `filesDir` is never reclaimed automatically, so something has to delete old recordings or the
 * app quietly accumulates ~21 MB per 90-minute meeting forever. [pruneUploaded] is that
 * something, and it only ever deletes a file whose audio is confirmed to be on Drive.
 */
object MeetingAudioStore {

    private const val DIR_NAME = "meetings"

    /**
     * Local audio is kept this long after recording even once it is safely on Drive, so
     * playback in the app stays instant for anything recent.
     */
    const val KEEP_LOCAL_DAYS = 30L

    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    fun fileFor(context: Context, meetingId: Long): File =
        File(dir(context), "meeting_$meetingId.m4a")

    /**
     * Deletes the local copy of [meetingId]'s audio. Safe to call when there is none.
     *
     * Only ever call this once the audio is known to be on Drive — the local file is the sole
     * copy until then.
     */
    fun deleteLocal(context: Context, meetingId: Long) {
        runCatching { fileFor(context, meetingId).delete() }
    }
}
