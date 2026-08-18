# CLAUDE.md — Project Context

## Standing Instructions for Claude

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
- **Role**: Deputy, NSW SES (State Emergency Service) — Dubbo Unit
- **Primary device**: Android phone
- **Google account**: Google One Premium with 5 TB storage plan (use this for any cloud storage/backend needs — no need for additional paid services)

## Projects

### 1. SES Unit Dashboard (`com.carlmanning.sesdashboard`)
Native Android app (Kotlin + Jetpack Compose) that aggregates:
- myAvailability (SES) — operational/activity/OOAA requests
- Outlook personal SES inbox
- Outlook DBO Ops shared mailbox
- Microsoft Planner tasks
Uses Claude Haiku via Anthropic API (key stored in SharedPreferences) to triage action items.
Data is extracted by injecting JavaScript into WebViews.

### 2. Second Brain / ADHD Memory System — **Carl's Brain** (`com.carlmanning.carlsbrain`)
See architecture below.

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
- `memory.md` pre-seeded on first launch with Carl's known context (SES Deputy, Dubbo Unit, life buckets, etc.)
- Auto-updates with a low threshold for "important" — bias toward over-capture
- Claude appends silently after interactions; user can view/edit in Settings

### Anthropic API key
- Entered by user in Settings screen, stored in SharedPreferences (same pattern as SES Dashboard)

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

### NEXT MAJOR FEATURE — Journalling (flagged by Carl, not yet specced)

The next significant addition to the app is a **dedicated journalling feature with its own
page/screen**. Carl flagged this for future work; it has NOT been designed or scoped yet.

When picking this up, do not assume it is just "notes with a date". Establish first:
- How a journal entry differs from a note (separate entity, or a note with a journal flag?)
- Whether entries are per-day, free-form, or prompted (ADHD support suggests prompting may help)
- Whether Claude reads journal history for the daily briefing, and whether it may write to it
- Whether entries can be vault/private — likely yes, so vault-safe queries apply from day one
- Voice capture as an entry path, given the existing wake-word and transcription pipeline

Ask Carl these before building rather than inferring, since the answers change the schema.

### Phase 2 — status

- ~~Quick capture home screen widget~~ — **built** (`widget/QuickCaptureWidget.kt`)
- ~~Dashboard home screen widget~~ — **built** (`widget/DashboardWidget.kt`, vault-safe query)
- ~~Photo/image attachments on notes~~ — **built** (also on todos)
- SES Dashboard → Carl's Brain task sync (Planner tasks into SES bucket) — **not started**.
  The only Phase 2 item outstanding. Needs a decision on direction first: whether tasks are
  copied into Carl's Brain or merely mirrored, and what happens when one side completes a task.

### Cost estimate
- Google Drive API: Free (well within personal-use quota)
- Google Calendar API: Free
- Anthropic API: ~$0.25–$2/month with Haiku for most calls
- Total ongoing cost: API calls only, no server costs
