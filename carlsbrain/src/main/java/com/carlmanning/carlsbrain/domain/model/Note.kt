package com.carlmanning.carlsbrain.domain.model

data class Note(
    val id: Long = 0,
    val title: String,
    val content: String,
    val bucketId: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val attachments: List<String> = emptyList()  // Drive file IDs
)
