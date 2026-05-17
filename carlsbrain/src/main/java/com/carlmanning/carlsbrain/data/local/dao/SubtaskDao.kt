package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubtaskDao {
    @Query("SELECT * FROM subtasks WHERE todoId = :todoId ORDER BY sortOrder ASC, id ASC")
    fun getSubtasksForTodo(todoId: Long): Flow<List<SubtaskEntity>>

    @Query("SELECT * FROM subtasks WHERE todoId IN (:todoIds) ORDER BY todoId ASC, sortOrder ASC, id ASC")
    fun getSubtasksForTodos(todoIds: List<Long>): Flow<List<SubtaskEntity>>

    @Insert
    suspend fun insertSubtask(subtask: SubtaskEntity): Long

    @Update
    suspend fun updateSubtask(subtask: SubtaskEntity)

    @Delete
    suspend fun deleteSubtask(subtask: SubtaskEntity)

    @Query("DELETE FROM subtasks WHERE todoId = :todoId")
    suspend fun deleteSubtasksForTodo(todoId: Long)
}
