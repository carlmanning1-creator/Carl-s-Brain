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
    @Query("SELECT * FROM meetings ORDER BY recordedAt DESC")
    fun getAllMeetings(): Flow<List<MeetingEntity>>

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun getMeetingById(id: Long): MeetingEntity?

    @Insert
    suspend fun insertMeeting(meeting: MeetingEntity): Long

    @Update
    suspend fun updateMeeting(meeting: MeetingEntity)

    @Query("SELECT * FROM meetings WHERE title LIKE '%' || :query || '%' OR transcript LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' ORDER BY recordedAt DESC LIMIT 20")
    suspend fun searchMeetings(query: String): List<MeetingEntity>

    @Delete
    suspend fun deleteMeeting(meeting: MeetingEntity)

    @Query("SELECT * FROM meetings WHERE status = 'DONE' ORDER BY recordedAt DESC LIMIT :limit")
    suspend fun getRecentDoneMeetings(limit: Int): List<MeetingEntity>
}
