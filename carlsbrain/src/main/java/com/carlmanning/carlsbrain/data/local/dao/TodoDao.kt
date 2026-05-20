package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    @Query("SELECT * FROM todos ORDER BY priority ASC, dueDate ASC, createdAt DESC")
    fun getAllTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE bucketId = :bucketId ORDER BY priority ASC, dueDate ASC")
    fun getTodosByBucket(bucketId: Long): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE priority = :priority ORDER BY dueDate ASC, createdAt DESC")
    fun getTodosByPriority(priority: Int): Flow<List<TodoEntity>>

    @Query("""
        SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE b.isVault = 0
        ORDER BY t.priority ASC, t.dueDate ASC
    """)
    fun getNonVaultTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE isDone = 0 ORDER BY priority ASC, dueDate ASC")
    fun getActiveTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getTodoById(id: Long): TodoEntity?

    @Query("SELECT * FROM todos WHERE isSynced = 0")
    suspend fun getUnsyncedTodos(): List<TodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTodo(todo: TodoEntity): Long

    @Update
    suspend fun updateTodo(todo: TodoEntity)

    @Delete
    suspend fun deleteTodo(todo: TodoEntity)

    @Query("UPDATE todos SET isDone = :isDone, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun setTodoDone(id: Long, isDone: Boolean, updatedAt: Long = System.currentTimeMillis())
}
