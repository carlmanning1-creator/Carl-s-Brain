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

        /**
         * Journal entries had no tombstone, so a purged entry whose Drive file survived — the
         * delete call fails offline, and its result was never checked — was re-inserted by the
         * next pull as though it were new. Notes were protected against exactly this.
         */
        const val TYPE_JOURNAL = "JOURNAL"
    }
}
