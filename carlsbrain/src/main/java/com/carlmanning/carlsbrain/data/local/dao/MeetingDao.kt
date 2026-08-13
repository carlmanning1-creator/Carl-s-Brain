package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meetings WHERE deletedAt IS NULL ORDER BY recordedAt DESC")
    fun getAllMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun getMeetingById(id: Long): MeetingEntity?

    @Insert
    suspend fun insertMeeting(meeting: MeetingEntity): Long

    @Update
    suspend fun updateMeeting(meeting: MeetingEntity)

    @Query("SELECT * FROM meetings WHERE deletedAt IS NULL AND (title LIKE '%' || :query || '%' OR transcript LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%') ORDER BY recordedAt DESC LIMIT 20")
    suspend fun searchMeetings(query: String): List<MeetingEntity>

    @Delete
    suspend fun deleteMeeting(meeting: MeetingEntity)

    @Query("SELECT * FROM meetings WHERE status = 'DONE' AND deletedAt IS NULL ORDER BY recordedAt DESC LIMIT :limit")
    suspend fun getRecentDoneMeetings(limit: Int): List<MeetingEntity>

    @Query("SELECT * FROM meetings WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getDeletedMeetings(): Flow<List<MeetingEntity>>

    @Query("UPDATE meetings SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDeleteMeeting(id: Long, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE meetings SET deletedAt = NULL WHERE id = :id")
    suspend fun restoreMeetingFromBin(id: Long)

    @Query("DELETE FROM meetings WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun purgeOldDeletedMeetings(cutoffMs: Long)

    @Query("SELECT * FROM meetings WHERE status = 'FIREFLIES_PROCESSING' AND deletedAt IS NULL")
    suspend fun getFirefliesProcessingMeetings(): List<MeetingEntity>

    @Query("SELECT firefliesId FROM meetings WHERE firefliesId IS NOT NULL AND deletedAt IS NULL")
    suspend fun getAllFirefliesIds(): List<String>

    @Query("SELECT * FROM meetings WHERE firefliesId = :firefliesId LIMIT 1")
    suspend fun getByFirefliesId(firefliesId: String): MeetingEntity?

    @Query("UPDATE meetings SET bucketId = :bucketId WHERE id = :id")
    suspend fun setBucket(id: Long, bucketId: Long?)
}
