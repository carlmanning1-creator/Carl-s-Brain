package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity
import kotlinx.coroutines.flow.first

class MidnightCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val db = AppDatabase.getInstance(applicationContext)
            db.todoDao().archiveAllCompleted()

            val cutoff = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)

            // Write tombstones before hard-purging so pull sync never re-inserts auto-expired items
            val expiredTodos = db.todoDao().getDeletedTodos().first()
                .filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
            val expiredNotes = db.noteDao().getDeletedNotes().first()
                .filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
            db.tombstoneDao().insertAll(
                expiredTodos.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_TODO) } +
                expiredNotes.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_NOTE) }
            )

            db.noteDao().purgeOldDeletedNotes(cutoff)
            db.todoDao().purgeOldDeletedTodos(cutoff)
            db.meetingDao().purgeOldDeletedMeetings(cutoff)

            // Purge tombstones older than 180 days — they're no longer needed once Drive
            // has been synced well past the item's deletion date
            val tombstoneCutoff = System.currentTimeMillis() - (180L * 24 * 60 * 60 * 1000)
            db.tombstoneDao().purgeOld(tombstoneCutoff)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
