package com.carlmanning.carlsbrain.domain.model

/**
 * Priority levels for todos.
 *
 * [rank] is the value stored in the database and used for ordering: lower rank = more urgent.
 * Storing as an Int (not the enum name) means SQL `ORDER BY priority ASC` produces the correct
 * urgency order rather than alphabetical order.
 */
enum class Priority(val rank: Int, val displayName: String) {
    URGENT(0, "Urgent"),
    HIGH(1, "High"),
    NORMAL(2, "Normal"),
    SOMEDAY(3, "Someday");

    companion object {
        fun fromRank(rank: Int): Priority =
            entries.firstOrNull { it.rank == rank } ?: NORMAL
    }
}
