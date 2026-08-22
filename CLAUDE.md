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

### Fast-forward master after every push — standing permission

After pushing to the working branch, immediately fast-forward `master` to the same commit:

```
git push origin HEAD:master
```

Carl has given explicit, standing permission for this. Do it without asking, every time, as
part of the push — not as a separate task to remember later.

The reason it matters: Vercel builds the web app from `master`, and Android Studio builds the
phone from the working branch. When those two drift, Carl deploys the web app and gets code
from months ago with no error to explain it — which is exactly what happened when `master` sat
137 commits behind and the Journal tab simply was not there.

Two rules, and the first is not negotiable:

- **Fast-forward only. Never force-push `master`.** Verify first with
  `git merge-base --is-ancestor origin/master HEAD`. If that fails, something landed on
  `master` independently: stop, and tell Carl rather than resolving it unilaterally.
- **Push the working branch first**, then `master`. If the branch push fails, `master` must not
  move ahead of it.

Note that this puts unbuilt code on `master`, since the Kotlin cannot be compiled here. That is
the accepted trade: a failed Vercel build keeps the previous deployment live, and a Kotlin error
does not affect the web app at all — whereas a stale `master` fails silently and invisibly.

### Editing Kotlin from a shell heredoc — don't

Twice now a Python heredoc has written `"\n"` into a Kotlin file as a real newline, producing an
unterminated string literal that compiles nowhere but passes every brace-balance check, because
the braces *are* balanced. Carl finds it, a full Gradle build later.

Use the Edit tool for any Kotlin containing backslash escapes or raw-string delimiters. If a
script really is the right tool, grep the result for the signature afterwards: a line ending in
`("` or a line starting with `")`.

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
- **Attachments** (v2.7, migration 23→24) reuse the comma-separated Drive-id encoding notes and
  todos already use. Attachments on a private entry are uploaded like any other — Carl's
  explicit call. "Private" means hidden from ordinary views, Claude and search; it has never
  meant encrypted, and the app must not imply otherwise.
- **Templates** (v2.8, migration 24→25) are Carl's own, built in-app — a named set of *typed*
  questions, not text skeletons. Field types: SCALE (with anchors at each end), CHOICE,
  MULTI_CHOICE, TEXT, LONG_TEXT. Two are seeded — Training and Kink — from his own spec, and
  they are ordinary editable rows, not privileged.
  - **Answers are stored as data** (`answersJson`) *and* rendered into the entry's `content`.
    The rendered text is what search, sharing, the Drive `.md` and Claude all read, so none of
    them needs to know templates exist; the structured answers are what make "chart my training
    scores against my sleep" possible later.
  - **Each entry snapshots the field definitions it was answered against.** Editing a template
    must never retroactively change what a past entry meant. Same principle as storing the
    prompt with the entry.
  - **Field ids are stable and independent of labels**, so renaming a question keeps its history.
    Two templates can deliberately share a field id to make answers comparable across them.
  - **Anchors are not decoration.** A scale without them is uninterpretable a year later. All
    five training scales run 1 = bad → 10 = good; `higherIsBetter` exists for any reversed scale
    Carl builds later, so nothing analysing them reads a scale upside-down.
  - **Option lists are shared entities**, not per-field copies, because Main and Secondary
    Activities must stay in step when he edits the list. Entries keep the option *text* they
    recorded, so deleting an option never rewrites history.
  - **Drafts are rows** with `isDraft`, listed with a red DRAFT marker at Carl's request. Every
    other journal query excludes them **in SQL** — Claude, search, the private count, the Drive
    push — because a screen that forgets to filter is exactly how this project has leaked before.
    Drafts never sync to Drive.
  - Templates and option lists round-trip via `journal_templates.json` so a new phone arrives
    with them built. Insert-only and matched on name; deleted ones never come back.
- **Sharing** an entry sends its text through the system share sheet. Private entries can be
  shared, behind a confirmation, for the same reason attachments are uploaded: refusing would
  imply a protection that does not exist. Attachments are never included in a share.
- **Buckets on entries and templates** (v2.10, migration 26→27). An entry's bucket is optional
  and independent of `isPrivate` — null means unfiled, and unfiled is the ordinary case, so
  nothing may quietly default a journal entry into Family the way captures do.
  - **A vault bucket hides the entry**, exactly as it hides a note. The exclusion is in SQL in
    all four visible-entry queries (list, Claude, search, private count), not in the screens.
    The private count deliberately counts private OR vault-bucketed: a count that disagrees
    with what the list is withholding is worse than no count.
  - A template carries a default bucket, applied to entries started from it.
  - The bucket travels to Drive as a **name**, in the same `<!-- bucket: … -->` comment notes
    use, because ids are per-device. On pull it is matched by name and **never created**: the
    bucket list, vault flags included, is merged earlier in the same sync, and inventing one
    here would produce a public bucket shadowing a vault bucket that had not arrived yet. A
    blank comment means the writer knows nothing about journal buckets (the web app does not),
    so it leaves the local bucket alone rather than clearing it.
  - **The web app applies the same rule**: `/api/drive/journal` withholds an entry that is
    private *or* in a vault bucket, and re-emits the bucket comment on save. Filtering on
    `isPrivate` alone was a real leak — on the phone the bucket is what hides the entry, so
    ticking Private as well is the exception, not the rule.
- **Per-template reminders** (v2.10) are a `DOW:HH:MM` rule on the template — `SUN:10:00` for
  training after Sunday CrossFit. Blank is the default and stays the default. AlarmManager, not
  WorkManager, matching the digest and todo reminders: it has to land on a minute. Alarms are
  rebuilt wholesale on save, on delete and at launch, because AlarmManager holds no readable
  list and a soft-deleted template would otherwise keep nagging forever. Tapping opens the
  Journal, not the template — one deleted since the alarm was set would open nothing.

### The sync wire format — v2 (August 2026)

`todos.json` rows carry a `schema` number, and the entity files carry metadata comments. What
each side may assume:

- **`schema: 1` (or absent)** predates subtasks. A `null` on such a row means *the writer did not
  know this field*, so the reader keeps its own value. This is why clearing a reminder on the web
  never stuck: the phone read the null as ignorance and put the reminder back.
- **`schema: 2`** promises that the writer sent every field it knows. A `null` reminder or
  estimate is now a deliberate clear — and clearing a reminder cancels its alarm, or it fires
  anyway.
- **Absent is not empty.** `subtasks`, `attachments` and `isArchived` are *nullable*, and null
  means "not stated". A web editor merging onto a row that predates subtasks omits the field
  entirely, and reading that as an empty list would delete every subtask on the phone the moment
  a to-do was edited on the laptop. Only an explicit `[]` deletes.
- **Subtasks travel by value, with no ids.** Ids are per-device autoincrement values that mean
  nothing elsewhere, so the list is replaced wholesale rather than reconciled row by row —
  matching titles up would guess wrong on a rename.
- **A new recurrence occurrence starts with no subtasks** and un-archived, on both clients,
  because the phone's `spawnNextRecurrence` copies the to-do row and subtasks live in their own
  table. Attachments do come along.
- Notes carry `<!-- attachments: … -->`; journal entries additionally carry `<!-- answers: … -->`
  and `<!-- mood: … -->`. **Anything that rewrites a file must re-emit every comment it did not
  author**, or editing on one device silently strips what the other wrote.
- When a pull replaces a journal entry's rendered text, it replaces `answersJson` too. Keeping
  the old structured answers would leave the entry reading one way and charting another, which is
  precisely what the per-entry field snapshot exists to prevent.
- **Journal entries have tombstones** (`TombstoneEntity.TYPE_JOURNAL`), like notes and to-dos. A
  purged entry whose Drive file outlived it was being re-inserted as new.
- Files already on Drive are re-published once per format change, behind their own flag —
  `wireV2Republished` is separate from `noteBucketsRepublished` precisely because the older flag
  is already set on any device that has run 2.11.1.

### What the web app can and cannot do (August 2026)

The web app is a companion, not a second phone. It reads the same Drive folder and may edit
what it fully understands; anything it does not understand it carries through untouched.

- **Buckets come from `buckets.json`**, via `/api/drive/buckets`, not from a hardcoded six.
  Vault buckets are withheld while the vault is locked — the *name* is the sensitive part.
- **Meeting action items live in `actions.json`**, written by the phone from the same column
  its own screen reads. The old `[ACTION:]` scrape of summary.md is a fallback only: the phone
  strips those markers before saving, so scraping a phone-recorded meeting always found none.
  Approving one on the web rewrites the file — leaving it there makes the phone treat the
  meeting as a permanent loose thread.
- **The phone pulls meeting edits back**, gated on `updatedAt` in `meta.json`, for meetings
  recorded in the last 60 days and at most 25 of them. Bounded because each check costs a Drive
  read and folder `modifiedTime` does not move when a child file is rewritten. Correcting a
  transcript is the one meeting job the laptop does better; before this the phone silently
  overwrote it.
- **Web-created meetings write their own `meta.json`.** Without it they had no bucket, so they
  could never be vault-filtered.
- **Journal templates are readable on the web, never editable.** Carl builds them on the phone,
  where the typed fields live. A template whose default bucket is a vault bucket, or that is
  private by default, is withheld while locked.
- **The web calendar reads every calendar, minus the excluded ones**, from `preferences.json` —
  the same rule `CalendarRepository` applies, primary never excludable. It used to read only
  `primary`, so the laptop showed a strictly smaller diary than the phone and anything on a
  shared or SES calendar was invisible with nothing to say a calendar existed.
- **The web briefing uses the same inputs as the phone's** — calendar, to-dos, overdue count,
  recent note titles, and the last fortnight of journal entries with the same instruction not to
  quote them. Every one of those comes from a route that filters server-side, so the prompt
  cannot contain something the vault is hiding.

### Deleting, and memory.md

- **A delete is a stamp, not a removal.** The web app rewrites `note_<id>.md` /
  `journal_<id>.md` with `<!-- deletedAt: … -->`; the phone reads that and soft-deletes, so the
  item lands in Recently Deleted and stays recoverable for 90 days. Trashing the file did not
  work: the phone treats a synced item whose file has vanished as a *lost upload* and re-uploads
  its own copy, so a web delete came back within fifteen minutes. Both readers hide a stamped
  file. (The phone still trashes on its own deletes — its Room row is the recycle bin there.)
- **memory.md is never blind-written.** The web GET returns Drive's `modifiedTime` and the PUT
  sends it back; a mismatch is a 409 telling Carl the phone learned something since the page
  loaded. On the phone, `MemoryLearner` appends against a **fresh read** under a mutex, never
  against its cache — the cache is for building prompts, not for writing from.
- **The web app does not seed memory.md.** Its seed described Carl as an SES Deputy, which he
  has not been since his role changed, and this file is prepended to every Claude call on both
  clients. `DriveRepository.INITIAL_MEMORY` on the phone is the only seed, kept in step with the
  About Carl section above.
- **The web session refreshes its Google token.** Only the access token used to be stored, so
  after an hour every call 401'd while the middleware still saw a valid session. A failed
  refresh flags the token and the middleware treats it as signed out.

### Web app — routes that take a Drive id

The web app's OAuth token has full `drive` scope, so any route that accepts an id can be pointed
at *any* file in Carl's Drive. Single-user does not mean single-origin. Three rules, in
`lib/driveQuery.ts` (pure) and `lib/driveGuards.ts` (Drive-aware):

- **Ids are validated before use.** Every entity id either client produces is an integer; anything
  else is refused. An id becomes both a filename and part of a Drive query, so a crafted one could
  match a file the route was never asked about — `memory.md`, overwritten with note content.
- **Values interpolated into a Drive `q` string are escaped.** It is a query language with quoted
  literals; an unescaped quote turns the rest into syntax.
- **The vault rule applies to files, not just to lists.** The meetings list always filtered vault
  buckets, but the audio and share routes took any id — so a folder id captured while the vault
  was open kept working after it was locked. Both now verify the file is inside SecondBrain and
  not in a vault bucket, and both **fail closed**. Sharing especially: it publishes to "anyone with
  the link", which is not reversible in practice, so a file whose bucket cannot be determined is
  refused rather than assumed public.

### Rules the August 2026 reliability pass established

Small rules, each of which had already been broken somewhere:

- **A save triggered by leaving a screen runs on `CarlsBrainApp.appScope`, never `viewModelScope`.**
  Back-navigation *is* the pop that cancels `viewModelScope`, so the save that navigation
  triggers can die at its first suspension point. Same for anything after `onComplete()` —
  `MemoryLearner.learnFrom` calls go *before* it.
- **Every microphone owner performs the handshake.** Wake word, ambient buffer and meeting
  recording all park the others, wait `MIC_HANDOVER_MS`, and hand back on every exit path
  including `onDestroy`. `ACTION_STOP_BUFFER` stands the buffer down without touching the
  consent setting; `ACTION_DISABLE` is the one that turns it off.
- **`startForeground` is called first, unconditionally, in `onStartCommand`** — before deciding
  whether there is anything to do. A branch that turns out to be a no-op hands it back.
- **A guard flag set before suspending work is cleared on *every* return**, success or failure.
  `promoting` blocking all future recordings after one encoder failure is the shape to avoid.
- **Chat is a vault-closed surface.** It files into non-vault buckets only, matching
  `MeetingViewModel.autoSortBucket`, because its completion path is vault-filtered — it must not
  create what it can never find.
- **Ticking a to-do off never regenerates the briefing.** `toggleDone` refreshes the to-do
  surfaces and loose threads only; `loadData()` costs a calendar fetch and a paid Claude call.
- **Completion always goes through `CompleteTodoUseCase`** — Todos, Dashboard, Chat, voice, the
  notification action, and the web app's own route. Anything else silently ends a recurrence.

### Notes on Drive — the bucket comment is not optional

`uploadNoteFile` writes `<!-- bucket: … -->` whether or not the note has a title. It used to be
part of the titled branch only, so untitled notes reached Drive with no bucket at all — and a
reader with nothing to go on defaults to something public, which quietly un-vaults an untitled
note in a vault bucket. Two rules follow, and both matter:

- A **missing** bucket comment means *unknown*, never "Personal". The web app withholds
  unknown-bucket notes while the vault is locked, and writes no comment rather than inventing
  one when it saves a note that had none.
- Notes already on Drive without one are re-uploaded once (`noteBucketsRepublished`), so the
  unknown state is transient rather than permanent.

The pull honours it too, by name: matched wins, blank falls back to the default, and a name that
matches nothing **skips the note this sync** rather than guessing. An unmatched name usually means
buckets.json has not merged yet, and filing it into Family anyway is precisely how a vault note
becomes a visible one; creating the bucket locally would produce a public bucket shadowing a vault
one. Skipping is recoverable — the next sync picks it up.

### Loose threads — BUILT (version 2.11, migration 27→28)

Surfacing *started-but-unfinished* work, which is the thing Carl's ADHD actually costs him.
Not a second to-do list: everything here already exists somewhere else in the app.

- **Detection is deterministic and offline** (`domain/loosethread/LooseThreadDetector.kt`).
  Claude is never asked *what* is loose — a model trawling the database for "loose ends" invents
  them. It is asked, separately and only when Carl opens the sheet, how to phrase the one thread
  already chosen.
- **Per-signal thresholds**, because the signals mean different things: part-ticked subtasks 7d,
  unapproved meeting action items 3d, journal drafts 2d, notes with a part-filled checklist 14d,
  pinned/urgent to-dos 5d. A note untouched for three days is just a note.
- Notes qualify **only** when they contain both ticked and unticked checkboxes. Every fortnight-old
  note being "loose" would drown the real signals — most notes are reference, not work.
- **One thread at a time**, oldest-touched first. A ranked list of eight is the problem, not the
  answer.
- `pendingActionItems` is a **JSON array**, not newline-delimited text. The detector decodes it
  and skips an empty list; testing `isNotBlank()` counted `"[]"` — what a fully-approved meeting
  stores — as an outstanding thread forever.
- **Three actions: Open / Snooze a week / It's dead**, recorded in `loose_thread_state`, keyed
  `KIND:refId` because a thread can point at four different tables. "It's dead" surfaces it never
  again and **deletes nothing** — Carl said stop asking, not throw it away.
- **Never synced.** A dismissal is a moment on one device; restoring a six-month-old one onto a
  new phone would silently hide work.
- Vault rules hold: non-vault DAO variants where they exist, and an explicit bucket filter for
  meetings and journal drafts, which are read through unfiltered queries.
- The Dashboard button exists **only when the count is non-zero**. A button that is always there
  stops being read.

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
- **Quiet hours applies to the buffer**, not just the wake word. The gate is
  `AmbientBuffer.accepting`, on the ring itself rather than in either capture loop, because the
  buffer has two possible feeders and the window has to mean the same thing to both. When this
  service owns the microphone it releases it outright — "not listening" should be true of the
  hardware, not just of what we do with the samples. Audio already buffered survives the window;
  only new capture stops. **Promotion deliberately ignores quiet hours**: tapping Record is an
  explicit instruction, and the window governs passive capture only.
- **Legal**: NSW is an all-party-consent state for private conversations, and Carl works for a
  government agency and volunteers with SES. Leaving the buffer on is continuous capture. The
  setting is the consent control; nothing may arm it automatically or re-enable it.

### Settings that follow Carl to a new device — BUILT (version 2.6)

`/SecondBrain/preferences.json`, written by `DriveSyncWorker`. This is **device migration, not
live two-way sync**, and the distinction is deliberate:

- **Pull once, then push.** A device takes its settings from Drive on its first sync after a
  fresh install, then publishes from then on. A change made on a second phone does not later
  appear on the first — that needs a changed-at stamp per setting, which was considered and
  deferred.
- **Never synced: the wake word and ambient buffer on/off switches.** These arm the microphone.
  A new device starts with both off and Carl arms them himself. Their tuning (buffer length,
  90-minute cutoff) does travel. Do not "helpfully" add the switches later.
- Also excluded: API keys (already in `settings.json`), biometric lock (restoring "on" onto a
  device with no enrolled biometric locks him out), and anything device- or moment-local.
- Included: digest and notification times, reminders/weekly review, quiet hours, journal prompt,
  briefing rules, excluded calendars, sort/kanban/swipe display modes.
- A pull reschedules the AlarmManager alarms; DataStore changing underneath them is invisible.
- Buckets round-trip too (`mergeBucketsFromDrive`), with vault flags restored one-way only — a
  pull can make a bucket private, never public.

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
