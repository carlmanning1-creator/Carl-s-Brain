package com.carlmanning.carlsbrain.domain.model

data class Todo(
    val id: Long = 0,
    val title: String,
    val bucketId: Long,
    val priority: Priority = Priority.NORMAL,
    val dueDate: Long? = null,
    val reminderAt: Long? = null,
    val recurrence: Recurrence = Recurrence.None,
    val calendarEventId: String? = null,
    val isDone: Boolean = false,
    val isPinned: Boolean = false,
    /** Rough time this to-do takes, in minutes. Null means "no estimate" — never guessed. */
    val estimateMinutes: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)
