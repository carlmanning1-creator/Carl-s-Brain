package com.carlmanning.carlsbrain.data.preferences

import kotlinx.serialization.Serializable

/**
 * The settings that travel to a new device, as published to `/SecondBrain/preferences.json`.
 *
 * ## What this is, and what it is not
 * This is **device migration**, not live two-way sync. A device pulls once — the first time it
 * syncs after a fresh install — and pushes from then on. So a new phone inherits Carl's whole
 * setup instead of starting blank, but a change made on phone B does not later appear on phone
 * A. Doing that properly needs a changed-at stamp per setting, which was considered and
 * deliberately deferred; this covers the case that actually happens, which is getting a new
 * phone.
 *
 * ## What is deliberately absent
 * - **Wake word and ambient buffer on/off.** These arm the microphone. A new device starts with
 *   both off and Carl turns them on himself; nothing may ever begin capturing audio on a phone
 *   where he did not arm it. Their *tuning* (buffer length, cutoff) travels — only the switches
 *   do not.
 * - **API keys.** Already carried by `settings.json`, which the web app also reads.
 * - **Biometric lock.** Restoring "on" onto a device with no enrolled biometric would lock Carl
 *   out of his own app.
 * - **Anything device- or moment-local**: Google connection state, cached briefing, busy mode,
 *   onboarding, the wake trigger log.
 *
 * Every field has a default, and unknown fields are ignored on read, so an older build reading
 * a newer file degrades to "settings I understand" rather than failing the whole sync.
 */
@Serializable
data class PreferencesSnapshot(
    // Schedules and notifications
    val morningDigestHour: Int = 6,
    val morningDigestMinute: Int = 30,
    val digestEnabled: Boolean = true,
    val remindersEnabled: Boolean = true,
    val weeklyReviewEnabled: Boolean = true,
    val notifMorningEnabled: Boolean = false,
    val notifMorningHour: Int = 7,
    val notifMorningMinute: Int = 0,
    val notifMiddayEnabled: Boolean = true,
    val notifMiddayHour: Int = 12,
    val notifMiddayMinute: Int = 0,
    val notifAfternoonEnabled: Boolean = true,
    val notifAfternoonHour: Int = 15,
    val notifAfternoonMinute: Int = 0,
    val notifEveningEnabled: Boolean = true,
    val notifEveningHour: Int = 18,
    val notifEveningMinute: Int = 0,
    val notifAiEnabled: Boolean = true,
    val wakeQuietEnabled: Boolean = false,
    val wakeQuietStartMin: Int = 22 * 60,
    val wakeQuietEndMin: Int = 6 * 60,

    // Claude behaviour
    val journalPrompt: String = UserPreferences.DEFAULT_JOURNAL_PROMPT,
    val briefingRules: List<String> = emptyList(),
    val excludedCalendarIds: List<String> = emptyList(),

    // Display
    val todosSortMode: String = "PRIORITY",
    val notesSortMode: String = "UPDATED",
    val todosKanbanMode: Boolean = false,
    val swipeToCompleteEnabled: Boolean = false,

    // Recording — length and cutoff only. The on/off switches never travel; see above.
    val ambientBufferMinutes: Int = 5,
    val meetingAutoCutoffEnabled: Boolean = true
)
