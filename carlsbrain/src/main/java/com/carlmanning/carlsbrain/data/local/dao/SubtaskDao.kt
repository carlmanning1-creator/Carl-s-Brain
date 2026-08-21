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

    /** Snapshot for the Drive push — a Flow would keep the sync worker waiting on a collector. */
    @Query("SELECT * FROM subtasks WHERE todoId = :todoId ORDER BY sortOrder ASC, id ASC")
    suspend fun getSubtasksOnce(todoId: Long): List<SubtaskEntity>

    /**
     * Replaces a todo's subtasks wholesale, used when a pull brings a newer copy.
     *
     * Delete-then-insert rather than a diff: subtask ids are per-device autoincrement values and
     * nothing outside this table references them, so preserving them across devices would buy
     * nothing and matching rows up by title would guess wrong on a rename.
     */
    @androidx.room.Transaction
    suspend fun replaceSubtasks(todoId: Long, subtasks: List<SubtaskEntity>) {
        deleteSubtasksForTodo(todoId)
        subtasks.forEach { insertSubtask(it.copy(id = 0, todoId = todoId)) }
    }
}
