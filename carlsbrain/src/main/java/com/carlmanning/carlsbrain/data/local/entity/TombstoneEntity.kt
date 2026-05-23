package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity

@Entity(tableName = "tombstones", primaryKeys = ["id", "type"])
data class TombstoneEntity(
    val id: Long,
    val type: String,
    val deletedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_TODO = "TODO"
        const val TYPE_NOTE = "NOTE"
    }
}
