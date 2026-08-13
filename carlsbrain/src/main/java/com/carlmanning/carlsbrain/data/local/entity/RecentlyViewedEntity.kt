package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recently_viewed")
data class RecentlyViewedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemType: String,   // "TODO" | "NOTE" | "MEETING" | "EVENT"
    val itemId: Long,
    val title: String,
    val bucketId: Long? = null,
    val viewedAt: Long = System.currentTimeMillis()
)
