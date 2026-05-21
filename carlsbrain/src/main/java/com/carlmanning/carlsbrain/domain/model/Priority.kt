package com.carlmanning.carlsbrain.domain.model

enum class Priority(val rank: Int, val displayName: String) {
    URGENT(0, "Urgent"),
    HIGH(1, "High"),
    NORMAL(2, "Normal"),
    SOMEDAY(3, "Someday");

    companion object {
        fun fromRank(rank: Int): Priority = entries.firstOrNull { it.rank == rank } ?: NORMAL
    }
}
