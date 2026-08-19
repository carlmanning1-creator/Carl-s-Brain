# CLAUDE.md — Project Context

## Standing Instructions for Claude

### Get approval before building

Carl approves the start of any substantial build. Scope it, agree it, then wait for an
explicit go-ahead before writing code. This is partly about direction and partly about him
managing token usage and usage windows, so "I have spare context, I may as well start" is
not a reason to skip it.

Small corrections, bug fixes and things he has just asked for directly do not need this —
it applies to features and multi-step work.

### Prefer multiple-choice questions

When gathering requirements, ask with concrete options rather than open-ended lists. Use the
AskUserQuestion tool. A long list of free-text questions is hard work to answer and, given
the ADHD context, likely to stall. Offer a recommendation among the options where there is a
sensible default.


### Code Quality Gate — MANDATORY before every commit or push
Before committing or pushing ANY code, Claude MUST perform a self-review pass covering:
1. **Unreachable / dead conditions** — check that every `if` branch, guard clause, and `when` arm can actually be reached given the real runtime state. Flag boolean flags that are set one way and never reset, making later checks permanently false.
2. **Callback lifecycle** — verify every callback registered (`pendingTtsOnDone`, `onDone`, `RecognitionListener`, etc.) has a guaranteed code path that invokes it. Identify any path where a callback is set but can be silently cleared or replaced without firing.
3. **State machine conflicts** — trace concurrent state flags (`isListening`, `isConversationActive`, `wakeWordActive`, etc.) through every entry point. Confirm no combination of events can leave the system in an unrecoverable state.
4. **Action overloading** — check that intents/actions sent between components mean the same thing to sender and receiver. Flag cases where the same action is repurposed for two different use cases (e.g., temporary pause vs permanent disable).
5. **Empty/null output paths** — check what happens when a function receives blank input or produces blank output. Confirm downstream consumers handle it safely rather than hanging silently.
6. **Cleanup on navigation** — for any component that acquires a resource (mic, TTS, wake word pause), verify that resource is released on ALL exit paths including back-navigation and lifecycle destruction.

If any of these checks reveals a problem, FIX it before committing. Do not push code with known logic errors — surface them to Carl and correct them first.

## About Carl Manning

- **Work**: Project Officer at Service NSW (SNSW). This is the day job — "work" in the app
  means SNSW unless SES is named.
- **SES**: Unit Volunteer, NSW SES, Dubbo Unit. No longer a Deputy or Unit Commander, so do
  not assume command, rostering or approval responsibilities.
- **Lives**: Dubbo, NSW.
- **Training**: Olympic weightlifting, with Grace.
- **Primary device**: Android phone.
- **Google account**: Google One Premium, 5 TB (use for any cloud storage/backend need — no
  additional paid services required).

### Household

Carl lives in Dubbo with Bec, Grace and their son Lucas.

- **Bec** — Carl's wife.
- **Grace** — Carl's girlfriend, and also Bec's girlfriend. Works at NSW SES as a Volunteer
  Engagement Officer. Trains Olympic weightlifting with Carl.
- **Lucas** — their son.

### Weekly shape

- **Work**: Mon–Fri, roughly 08:30–16:00, mostly from home.
- **Bec**: out ~08:00, back ~17:00, Mon–Fri. Reliable — safe for the briefing to reason with.
- **Grace**: home Tue and Wed, office otherwise, but irregular hours and occasional travel.
  Reference it, but hedge rather than assert.
- **Lucas**: nominally at school 09:00–11:15 weekdays, but often not. **Never treat school
  hours as guaranteed free time.**
- **Training** (with Grace): Sundays 08:00–10:00, CrossFit Dubbo. Otherwise 05:30–07:30 at
  Phoenix Strength and Recovery, West Dubbo — often Mon or Tue, often Thu or Fri.
- **SES training**: Tuesday nights, usually late. Wednesday mornings are therefore almost
  never a training day, and Wednesday generally starts slow.

Carl's preference is to **bias toward including context** rather than omitting it — he can
edit the briefing in-app if it is wrong.

### ADHD — what to actually compensate for

In his own words, the problems are **losing track of things he has started**, **ideas that
arrive and are gone before they are captured**, **working memory**, and **deciding what to
focus on**.

What follows from that:
- Surfacing *started-but-unfinished* work matters more than listing everything outstanding.
- Capture must stay effectively frictionless — this is the whole reason the wake word exists.
- When he asks what to do, **name one thing**. A ranked list of eight is the problem, not the
  answer.
- Do not make him hold state in his head across a conversation; restate the relevant context.

### Default bucket

**Family**, when a capture is genuinely ambiguous. Prefer an explicit signal in what he said;
fall back to Family only when there is nothing to go on.

This is the family, not a secret. Treat all three adults as ordinary household context when
Carl mentions them — the same way any partner or child would be. Do not treat the
relationship structure as sensitive, do not hedge about it, and do not route it to the vault:
the vault is for what Carl explicitly files there, not for inferring what ought to be hidden.
Equally, do not volunteer the detail where it is not relevant.

## Project

**Carl's Brain** (`com.carlmanning.carlsbrain`) — Second Brain / ADHD memory system.
The only project in this repository. See the architecture record below.

> A second app, SES Unit Dashboard (`com.carlmanning.sesdashboard`), previously lived here as
> the `:app` module. It was retired in August 2026 when Carl's SES role changed and the app
> stopped being useful, and the module was deleted. Do not resurrect it or build integrations
> against it. It remains in git history if it is ever genuinely needed.

## Second Brain — Architecture Decision Record

### Core concept
A dedicated Android app as Carl's external memory and ADHD support tool. Claude is the intelligent layer on top — not just a feature, but the operating system of the whole thing. Inspired by Microsoft Planner + Google Keep + Todoist, but with Claude as the unifying intelligence.

### Tech stack
- **Platform**: Native Android (Kotlin + Jetpack Compose)
- **Auth + Storage**: Google Drive API (OAuth 2.0 — same Google account as Calendar)
- **Calendar**: Google Calendar API (read + write)
- **Local DB**: Room (offline-first; syncs to Drive when connected)
- **AI**: Anthropic Claude API (Haiku for quick ops, escalate to Sonnet for planning/analysis)
- **Voice**: Android SpeechRecognizer (fast, on-device) + optional Claude cleanup pass
- **Notifications**: Android WorkManager + NotificationManager

### Google Drive storage structure
```
/SecondBrain/
  memory.md              ← Claude's permanent context file (prepended to every Claude call)
  /notes/
    /ses/
    /family/
    /work/
    /personal/
    /[vault-buckets]/    ← sensitive buckets hidden in normal views
    /other/
    /[user-created-buckets]/
  todos.json             ← structured: title, bucket, priority, due, recurrence, done, created
  /audio/                ← optional voice recordings
```

### Life buckets (initial set)
SES, Family, Work, Personal, Kink, Other
- User can create additional custom buckets and transfer items between buckets
- Any bucket can be marked as **vault** (private) in settings

### Security model
- **Whole-app biometric lock** on every open (fingerprint / face)
- **Vault area**: sensitive buckets are invisible in all normal views
  - Accessed via non-obvious gesture (long-press on brain icon in top bar)
  - Settings toggle: include vault items in dashboard and notifications (on by default, since app is already biometric-gated)
- Vault buckets excluded from any unprotected export or share actions

### Todo priority levels
Urgent / High / Normal / Someday

### Recurring todos
Supported from day one: daily / weekly / monthly / custom interval

### Screens
1. **Dashboard** — morning digest, upcoming GCal events, priority todos, recent notes summary
2. **Quick Capture** — text field + voice button → Claude auto-tags and sorts into bucket
3. **Notes** — filterable by bucket, searchable, full markdown view
4. **Todos** — by bucket/priority/due date, checkable, Claude prioritisation on demand, recurring tasks
5. **Chat** — full Claude conversation with `memory.md` as system context
6. **Calendar** — upcoming events, create events from notes/todos via natural language

### Claude integration scope
- Auto-sort new notes/todos into the correct bucket
- Transcribe + clean voice notes
- Summarise notes on demand
- Conversational chat interface (captures, retrieves, manages everything)
- Suggest solutions and prioritise tasks
- Build daily/weekly/life plans using calendar + todos + memory
- Update `memory.md` with important facts from interactions over time

### Voice notes
- Android SpeechRecognizer for transcription (on-device, fast)
- Optional Claude cleanup pass (punctuation, structure) — queued for when online
- Audio discarded after transcription; only the text is saved

### Claude memory strategy
- `memory.md` pre-seeded on first launch from the "About Carl Manning" section above — role,
  household, life buckets. Keep the two in step: a stale seed feeds wrong context into every
  Claude call in the app.
- Auto-updates with a low threshold for "important" — bias toward over-capture
- Claude appends silently after interactions; user can view/edit in Settings

### Anthropic API key
- Entered by user in Settings screen, stored in SharedPreferences

### UI / theme
- System default (follows Android dark/light mode — Material 3 Dynamic Color)

### Offline-first strategy
- Room database is the source of truth on-device
- Every write goes to Room immediately (instant UX)
- WorkManager syncs Room → Drive in background when connected
- Conflict resolution: last-write-wins (single-user app)
- Claude calls require internet; voice capture queues for cleanup when online

### Dashboard layout (top to bottom)
1. Claude's daily briefing — AI-generated paragraph: what's on today, what needs attention
2. Today's calendar events (chronological)
3. Urgent + High priority todos
4. Recent notes summary

### Reminders
- Android push notifications for time-sensitive todos/deadlines
- Morning digest notification — default 6:30 AM, user-configurable
- In-app digest on open (today's priorities + upcoming events)

### Journalling — BUILT (Phase A, version 2.5)

A dedicated Journal screen, sixth item in the bottom nav. Decisions, so they are not
re-litigated:

- **Separate entity** (`journal_entries`, migration 22→23), not a flagged note. Entries carry
  the prompt they were written against and their own privacy flag, and stay out of every Notes
  query by construction rather than by each query remembering to filter.
- **One editable prompt** in Settings → Journal, plus an "Ask Claude for a prompt" button on
  the Journal screen that generates one from the last week's entries. Blank prompt is valid and
  means a genuinely blank page.
- **Claude reads, never writes.** The daily briefing gets the last 14 days for pattern-spotting
  only, with explicit instructions not to quote entries back. `getEntriesForClaude` excludes
  private entries in SQL, so no call site can hand one to an API by accident.
- **Per-entry privacy**, independent of buckets. Private entries hide with the vault, are
  excluded from search while it is closed, and never reach Claude.
- **Voice entry** via `[JOURNAL:]` — the prompt tells Claude to keep Carl's own words rather
  than summarising them. Spoken entries are never private by default, since silently hiding one
  would mean he could not find it where he expected.
- Syncs to Drive as `journal_<id>.md`, with the same self-healing re-upload as notes.

### Ambient capture — BUILT (Phase B, version 2.6)

There is no separate "session" or "ambient" object. The rolling buffer is simply a way to start
a **Meeting** recording earlier than the moment Carl tapped Record, and the result goes through
the same Fireflies → Whisper → Claude path as any other meeting. Do not reintroduce the word
"session" for this.

- **Rolling buffer**: 5–20 minutes, adjustable in Settings, default 5. File-backed circular
  buffer in cacheDir (`data/audio/AmbientBuffer.kt`) — 20 minutes is 38 MB, too much to hold on
  the heap. Its own on/off setting, independent of the wake word.
- **Microphone ownership** is the constraint that shapes the whole design: Android will not give
  one app two usable AudioRecord clients. Wake word ON → the keyword-spotter loop in
  `VoiceCaptureService` feeds the ring. Wake word OFF → `AmbientBufferService` runs its own
  capture loop. Consequence: while the wake word is parked (quiet hours, active conversation)
  the buffer is not filling.
- **One continuous audio file**: on Record, the ring is drained into a `MediaCodec` AAC encoder
  (`data/audio/PcmAacEncoder.kt`) and live audio keeps feeding the same encoder. Two files plus
  concatenated transcripts was rejected — Fireflies takes one file per meeting, so two files
  means two transcriptions and two sets of speaker labels.
- **No live transcript** during a buffer-started recording: running SpeechRecognizer while the
  service holds AudioRecord risks the recogniser taking the mic and the encoder writing silence.
- **Triggers**: voice ("start recording" / "stop recording" — matched locally, not via a Claude
  marker, so it works instantly and offline), Quick Settings tile (`RecordTileService`, launches
  no activity), home widget, and the banner on the Meetings screen.
- **90-minute auto-cutoff**, switchable off in Settings. Applies to ordinary meetings too.
- **Transcription stays with Fireflies → Whisper.** On-device sherpa ASR was scoped and then
  dropped: speaker labels matter more for ambient capture than for anything else in the app
  ("who said they'd do that?"), and it removed a ~120 MB model download.
- **Legal**: NSW is an all-party-consent state for private conversations, and Carl works for a
  government agency and volunteers with SES. Leaving the buffer on is continuous capture. The
  setting is the consent control; nothing may arm it automatically or re-enable it.

Still outstanding from the Phase B discussion: **syncing preferences to Drive** so settings
survive a device change. Buckets already round-trip (`mergeBucketsFromDrive`, vault flags
restored one-way only — a pull can make a bucket private, never public).

### Phase 2 — status

- ~~Quick capture home screen widget~~ — **built** (`widget/QuickCaptureWidget.kt`)
- ~~Dashboard home screen widget~~ — **built** (`widget/DashboardWidget.kt`, vault-safe query)
- ~~Photo/image attachments on notes~~ — **built** (also on todos)
- ~~SES Dashboard → Carl's Brain task sync~~ — **cancelled** (August 2026). Carl's SES role
  changed, the SES Dashboard app was retired, and the sync is no longer wanted. Phase 2 is
  therefore complete.

### Cost estimate
- Google Drive API: Free (well within personal-use quota)
- Google Calendar API: Free
- Anthropic API: ~$0.25–$2/month with Haiku for most calls
- Total ongoing cost: API calls only, no server costs
