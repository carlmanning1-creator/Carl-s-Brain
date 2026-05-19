package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.data.local.AppDatabase

class MidnightCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val db = AppDatabase.getInstance(applicationContext)
            db.todoDao().archiveAllCompleted()
            val cutoff = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)
            db.noteDao().purgeOldDeletedNotes(cutoff)
            db.todoDao().purgeOldDeletedTodos(cutoff)
            db.meetingDao().purgeOldDeletedMeetings(cutoff)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
