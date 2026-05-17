package com.carlmanning.carlsbrain.domain.model

sealed class Recurrence {
    object None : Recurrence()
    object Daily : Recurrence()
    object Weekly : Recurrence()
    object Fortnightly : Recurrence()
    object Monthly : Recurrence()
    data class Custom(val intervalDays: Int) : Recurrence()

    fun toStorageString(): String = when (this) {
        is None -> "NONE"
        is Daily -> "DAILY"
        is Weekly -> "WEEKLY"
        is Fortnightly -> "FORTNIGHTLY"
        is Monthly -> "MONTHLY"
        is Custom -> "CUSTOM:$intervalDays"
    }

    companion object {
        fun fromStorageString(value: String): Recurrence = when {
            value == "NONE" -> None
            value == "DAILY" -> Daily
            value == "WEEKLY" -> Weekly
            value == "FORTNIGHTLY" -> Fortnightly
            value == "MONTHLY" -> Monthly
            value.startsWith("CUSTOM:") -> {
                val days = value.removePrefix("CUSTOM:").toIntOrNull() ?: 1
                Custom(days)
            }
            else -> None
        }
    }
}
