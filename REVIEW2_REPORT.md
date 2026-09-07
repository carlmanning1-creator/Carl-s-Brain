# Carl's Brain — full codebase review, pass 2

2026-09-07. Both clients: `carlsbrain/` (~44,900 lines Kotlin, 161 files) and `webapp/`
(~10,400 lines TS/TSX, 66 files), plus the manifest, Gradle files and resources.

Every one of the twenty modules in `REVIEW2_PROGRESS.md` was **read**, not swept — including the
Compose screens and the web components, which pass 1 covered by grep only. Per-module detail with
full reasoning is in `REVIEW2_FINDINGS.md`.

Three findings drafted during this pass were withdrawn or downgraded on checking, and are recorded
as such in the findings file rather than quietly dropped.

---

## Critical

- **[ui/screens/settings/SettingsViewModel.kt:936-940 · data/local/dao/JournalDao.kt:28-34]**
  Issue: `reassignAll` moves to-dos, notes and meetings off a bucket being deleted, but not journal
  entries or templates, which carry the same nullable `bucketId` with no foreign key.
  Risk: deleting a vault bucket orphans its journal entries' `bucketId`, and every journal vault
  gate is `NOT IN (vault ids)` — which an orphan satisfies. They become visible, searchable, and
  charted, silently and permanently.
  Fix: reassign `journal_entries` and `journal_templates` in `reassignAll`, and count journal
  entries in the pre-deletion dialog, which currently calls such a bucket empty.

- **[data/local/worker/DriveSyncWorker.kt:766 · 378]** Issue: both edit-pulls skip the row when the
  *text* is unchanged, and apply the bucket and privacy fields only after that guard.
  Risk: re-filing a note or journal entry into a vault bucket on the laptop, or ticking an entry
  Private there, never reaches the phone — the item stays fully visible on the device he uses.
  Fix: compare the whole payload, or drop the early return and let the `updatedAt` comparison
  above it decide, which is what it is there for.

- **[data/local/worker/MeetingRecordingService.kt:106-118 · ui/screens/meetings/MeetingViewModel.kt:294]**
  Issue: `onStartCommand` returns early on `isRecording` and on a missing id before any
  `startForeground`, and the caller uses `startForegroundService`.
  Risk: `ForegroundServiceDidNotStartInTimeException` kills the process. Reachable by tapping
  Record twice, or the tile plus the screen button. It is the rule CLAUDE.md states in as many
  words and the other two microphone services follow.
  Fix: claim foreground first, unconditionally, then hand it back on the no-op paths, as
  `AmbientBufferService.placeholderForeground` does.

- **[data/remote/DriveRepository.kt:1109-1133]** Issue: `listFiles` never paginates. Drive returns
  100 results by default and there is no `pageSize` or `nextPageToken` handling; the web app
  paginates at `webapp/src/lib/drive.ts:136-149`.
  Risk: notes past the first hundred never reach a new phone — and `DriveSyncWorker.kt:655-657`
  reads every absent id as a lost upload, so past 100 files the whole library is marked unsynced
  and re-uploaded every fifteen minutes, forever, starving the push budget.
  Fix: loop on `nextPageToken` with `pageSize=1000`; only then is the missing-file check safe.

- **[ui/screens/notes/NoteEditorViewModel.kt:394-399 · data/local/worker/ReminderReceiver.kt:62-70]**
  Issue: note reminders are scheduled under `noteId + 1_000_000` on the to-do alarm, and the vault
  check looks that key up in the **to-dos** table — finds nothing, and reads null as "nothing to
  check".
  Risk: a reminder on a note in a vault bucket puts its title on the lock screen, outside both the
  biometric gate and the vault.
  Fix: carry the entity type in the alarm extras and check the right table, or give note reminders
  their own receiver.

- **[res/xml/file_provider_paths.xml:3 · ui/screens/meetings/MeetingDetailScreen.kt:668-678]**
  Issue: the FileProvider declares only a `cache-path` root, while meeting audio was moved to
  `filesDir` (`MeetingAudioStore.kt:37-38`). `getUriForFile` throws on a path outside every root,
  and the call is unwrapped.
  Risk: tapping Share → Audio on any meeting crashes the app outright.
  Fix: add `<files-path name="meetings" path="meetings/" />` and wrap the `startActivity`, as the
  top-bar share at line 268 already does.

- **[ui/screens/chat/ChatViewModel.kt:726-753 · data/local/dao/MeetingDao.kt:63-64]** Issue:
  `loadRecentMeetings` uses `getRecentDoneMeetings(5)` — the one meeting query with no vault
  variant — and puts each meeting's title, summary, action items and transcript excerpt into the
  Chat system prompt.
  Risk: Chat is documented in that same file as unconditionally vault-closed everywhere else, so a
  vault meeting's contents go to the API on every Chat message and can be quoted back with the
  vault shut.
  Fix: add `getRecentDoneNonVaultMeetings` beside the existing non-vault pairs and use it.

- **[webapp/src/lib/driveGuards.ts:120-131 · webapp/src/app/api/drive/share/route.ts:43-49]**
  Issue: `fileIsShareable` treats any file whose parent is not the SecondBrain root as a meeting
  file. `media/` is also a child of the root and has no `meta.json`, so it resolves to "unsorted"
  and the guard returns true.
  Risk: `POST /api/drive/share` publishes any file id to "anyone with the link", irreversibly — so
  every attachment, including those on vault notes and private journal entries, is shareable with
  the vault closed.
  Fix: identify the folder by name; only a child of `meetings/` gets the meeting rule, and a file
  under `media/` must resolve its owning item's bucket or be refused.

- **[webapp/src/app/settings/SettingsContent.tsx:69-84 · 135-143 · webapp/src/lib/drive.ts:596-608]**
  Issue: a failed `GET /api/drive/memory` leaves `memory` and `memoryVersion` empty and the
  textarea editable; saving sends `modifiedTime: ""`, which `updateMemory` reads as "skip the
  conflict check".
  Risk: one edit and one Save erases memory.md entirely, with the guard that exists to catch this
  disabled by the same missing value. `MemoryEditorViewModel` calls this "the single most
  destructive path in the app" and it was closed on the phone; the web still has it.
  Fix: a `loadFailed` flag that shows a Retry and blocks saving, and make `updateMemory` require an
  explicit "no file" signal rather than inferring it from an empty string.

---

## High

- **[carlsbrain/schemas/…AppDatabase/]** Issue: exported schemas exist for 22, 23, 25, 26, 28 and
  29 — 24, 27 and 30 are missing, and 30 is current.
  Risk: `MigrationTest` can only verify a step whose start and end schemas both exist, so 23→24,
  26→27 and 29→30 are untested by construction. A bad migration is a wiped database.
  Fix: regenerate and commit the three, and add the missing `MigrationTestHelper` cases.

- **[BootReceiver.kt:105-119]** Issue: the wake word and ambient buffer are restarted from
  `BOOT_COMPLETED` with `startForegroundService`; Android 14+ refuses a `microphone`-type service
  started from that exemption, and `step()` records the refusal and carries on.
  Risk: "Hey Brain" is very likely dead after every reboot, silently — the toggle still reads on.
  Fix: verify on the device; if refused, notify that it needs re-arming and start it from
  `MainActivity` on the next foreground launch.

- **[data/local/worker/FirefliesSyncWorker.kt:189-215]** Issue: every sync writes each new
  meeting's title and 200 characters of summary into memory.md.
  Risk: memory.md is prepended to every Claude call on both clients and is never re-filtered, so a
  meeting later moved into a vault bucket keeps its content in every prompt indefinitely. It is
  also the memory.md shadow-copy shape that was removed for to-dos, rebuilt for meetings.
  Fix: drop the section, or re-derive it each run from the meeting rows excluding vault buckets.

- **[data/local/worker/VoiceCaptureService.kt:784-788]** Issue: `ERROR_RECOGNIZER_BUSY`,
  `ERROR_CLIENT`, `ERROR_AUDIO` and the `else` branch retry on a timer with no attempt counter;
  only `ERROR_NO_MATCH` is counted.
  Risk: a persistent recogniser failure loops forever at ~1/second with `isConversationActive`
  latched true — the wake word can never restart, the mic indicator stays lit, the battery drains,
  and there is no exit short of killing the app.
  Fix: count consecutive errors of any kind and end the conversation past a small ceiling.

- **[data/export/BrainExporter.kt]** Issue: the export writes notes, to-dos, meetings, events,
  buckets and memory.md — and contains no reference to the journal at all. Chat threads and
  subtasks are missing too.
  Risk: the file's header calls this "the thing that survives the app itself", and the most
  personal record in it, with all its template answers and Trends history, is silently absent.
  Fix: add journal entries and templates honouring both halves of the journal vault rule behind
  `includeVault`, plus subtasks and chat threads.

- **[data/health/HealthRepository.kt:88-165]** Issue: none of the four `readRecords` calls follows
  `pageToken`; Health Connect returns at most 1000 records per request.
  Risk: a Garmin-bridged phone exceeds a page in a 30-day window, and `readSteps` then gap-fills
  the days it never read with **0** — a confident zero fed into the Health screen and into the
  health context on Claude's voice prompt.
  Fix: loop until `pageToken` is null, and never gap-fill a day the query did not cover.

- **[ui/screens/dashboard/DashboardViewModel.kt:519 · 590-611]** Issue: every `loadData()` creates
  a to-do for every timed calendar event today and tomorrow, from every non-excluded calendar, in
  the default bucket — unconditionally.
  Risk: shared, birthday and holiday calendars all mint to-dos, and `singleEvents=true` means every
  recurrence of a recurring event gets its own. The briefing prompt twenty lines above tells Claude
  those calendars are noise while the importer files them as work.
  Fix: import from the primary calendar only, or gate it on an explicit setting — and offer it
  rather than doing it.

- **[ui/screens/todos/TodoEditorViewModel.kt:172-196 · ui/screens/todos/TodoEditorScreen.kt:144]**
  Issue: the to-do editor's "File" button launches `GetContent("*/*")` into `addAttachment`, which
  calls `uploadPhoto` — naming the file `.jpg` and storing a bare id. The note editor has a proper
  `addFile` with the `file:<name>:<id>` encoding.
  Risk: a PDF attached to a to-do is uploaded under a `.jpg` name, loses its real filename
  permanently, renders as a non-clickable icon, and cannot be identified or opened again.
  Fix: give the to-do editor the same `addFile` path, and make the non-image tile open the file.

- **[ui/screens/chat/ChatViewModel.kt:263-265 · 365-378]** Issue: `parseAndCreateTodos` runs before
  `parseAndCompleteTodos` on the same reply, and the completion path has none of the exact-match
  preference, ambiguity refusal or "exclude what this reply created" guard that
  `VoiceCaptureService.kt:1018-1099` was given.
  Risk: a to-do Chat creates is immediately a candidate for `[DONE:]` in the same reply — after
  which the nightly cleanup archives it and it vanishes as though never created. An ambiguous
  title silently completes the wrong one.
  Fix: lift the voice path's guards into a helper both surfaces call.

- **[webapp/src/lib/recurrence.ts:16-40 · domain/usecase/CompleteTodoUseCase.kt:104-118]** Issue:
  `nextDueDate` steps one interval from the old due date; the phone steps until the date is in the
  future, with a comment explaining that otherwise a task fallen behind can never be caught up.
  Risk: this file's own header says it mirrors `CompleteTodoUseCase` and that a disagreement fails
  silently. They disagree — ticking an overdue recurring to-do on the laptop spawns another
  already-overdue one.
  Fix: port the catch-up loop and its bound.

- **[webapp/src/lib/drive.ts:652-677 · 900-915]** Issue: `getNotes` and `getChatThreads` still use
  an unbounded `Promise.all` and drop failures with a bare `return null` — no concurrency bound and
  no unreadable count. The journal loader twenty lines above has both.
  Risk: Drive answers some of a few hundred simultaneous requests with 403
  `userRateLimitExceeded`, and the dropped items "read exactly like data loss" — the loader's own
  words. Notes is the larger library.
  Fix: use `mapWithConcurrency` and return an `unreadable` count for both.

- **[webapp/src/app/api/meetings/process/route.ts:63]** Issue: the action-item regex has an
  optional closing bracket and a lazy bucket group, so it captures the shortest bucket that lets
  the match succeed — a single character.
  Risk: exactly the bug documented at `MeetingViewModel.kt:61-71`. Every action item from a
  web-processed meeting is filed under a bucket called "W", and `ork]` is left in the summary.
  `app/api/drive/meetings/route.ts:17` has the corrected form, so the two disagree.
  Fix: export and share the corrected `ACTION_REGEX`.

- **[webapp/src/app/api/drive/meetings/route.ts:189-217 · webapp/src/lib/drive.ts:422-444]** Issue:
  the meetings list fans out an unbounded `Promise.all` over every folder, four reads each — and
  `readFileByName` has no try/catch, so one failure rejects the whole thing.
  Risk: eight Drive round trips per meeting at once; a single rate-limited response returns 500 and
  the Meetings page shows nothing at all. Notes and journal both got per-file catches; meetings got
  neither that nor a bound.
  Fix: bound the concurrency, catch per folder, and report how many could not be read.

- **[webapp/src/app/api/drive/todos/route.ts:52-116]** Issue: `POST` merges the incoming to-do onto
  the stored row and returns the merged row, with no vault check anywhere in the handler.
  Risk: `GET` is carefully vault-filtered server-side; `POST` hands the full row back, so a request
  naming a vault to-do's id echoes its title, bucket and due date with the vault closed — and can
  move a to-do into or out of a vault bucket.
  Fix: 404 when the stored row is vault-bucketed and the vault is not open, and refuse a `bucket`
  that names one.

- **[webapp/src/app/api/drive/todos/route.ts:56-58]** Issue: `incoming.id` is never run through
  `validEntityId`, unlike the sibling notes and chat routes.
  Risk: a non-numeric id is written into `todos.json`, and the phone parses that file into
  `TodoSyncDto` where `id` is a `Long`. One bad row throws, `getOrElse { return }` swallows it, and
  the phone's entire to-do sync stops working permanently — nothing rewrites the file.
  Fix: validate on the way in.

- **[webapp/src/components/meetings/MeetingRecorder.tsx:89-102]** Issue: the unmount cleanup stops
  recognition and the timer, and never stops the `MediaRecorder` or its `getUserMedia` tracks.
  Risk: leaving the page mid-recording holds the browser microphone open, indicator lit, until the
  tab closes. This is the "cleanup on navigation" item in CLAUDE.md's own pre-commit gate, on the
  resource it names first.
  Fix: stop the recorder and every track in the cleanup, via a ref.

---

## Medium

- **[carlsbrain/build.gradle.kts:20]** `versionName` is "2.13" against a documented 2.21 and
  `versionCode` has not moved in three feature versions, so no crash report can be tied to a build.
  Fix: bump both, as part of the release step.
- **[carlsbrain/build.gradle.kts:36-52]** The release `signingConfig` is assigned unconditionally
  though its own comment says an absent keystore means signing is "simply skipped"; the config is
  built from null properties. `assembleRelease` fails inside the signing task instead.
  Fix: build and assign it only when the keystore exists.
- **[CarlsBrainApp.kt:288-302 · 266-286 · 363-375]** All three periodic workers use
  `ExistingPeriodicWorkPolicy.KEEP`, freezing period and constraints at first install — a changed
  sync interval never takes effect and nothing says so. Fix: `UPDATE`.
- **[CarlsBrainApp.kt:119-127]** The template seeder and journal-reminder rearm are in a bare
  `runCatching` with no `ErrorLog`, unlike every other guarded startup step — a failed rearm stops
  every journal reminder invisibly. Fix: record the failure, and separate the two steps.
- **[CarlsBrainApp.kt:139-252 · 128-134]** Four startup steps run unguarded in `onCreate`;
  `getSystemService` and `WorkManager.getInstance` can both throw and take the process with them.
  Fix: wrap each, as the coroutine steps already are.
- **[MainActivity.kt:193-231]** The vault PIN doubles as the whole-app unlock when biometrics are
  dismissed, so shoulder-surfing four digits collapses both gates. Fix: Carl's call — separate app
  PIN, or biometric-only for the vault once inside.
- **[BootReceiver.kt:28 · 120-123]** The `goAsync` lease covers DataStore reads, a Room query, an
  unbounded reminder loop and two service starts on a just-booted device; past ~10 s Android may
  kill it mid-rearm. Fix: hand the reminder rebuild to a `OneTimeWorkRequest`.
- **[data/local/dao/MeetingDao.kt:79-88 · 93-94]** `softDeleteMeeting`, `restoreMeetingFromBin` and
  `setBucket` change what a meeting means without moving `updatedAt`, which the meeting pull is
  gated on — so filing a meeting into a vault bucket is discarded as stale. Fix: move the stamp.
- **[data/local/dao/NoteDao.kt:164-171 · TodoDao.kt:240-247]** `moveAllToBucket` and both
  `restore*FromBin` clear `isSynced` but not `updatedAt`, so the other device throws the change
  away. Fix: set it.
- **[data/local/dao/JournalTemplateDao.kt:14-15 · ui/screens/journal/JournalViewModel.kt:80]**
  `getTemplates()` has no vault variant, so a private-by-default or vault-bucketed template has its
  name on the Journal chips and in the manager with the vault closed. The web withholds exactly
  these. Fix: add the filtered query and pass the vault state.
- **[data/local/AppDatabase.kt:512]** `fallbackToDestructiveMigrationOnDowngrade(dropAllTables)`
  wipes unsynced captures, drafts, the bin and all loose-thread state on any rollback. Fix: keep
  it, but record that it fired and force a full re-pull afterwards.
- **[data/local/worker/MidnightCleanupWorker.kt:110-113]** The whole worker is one `runCatching`
  returning `Result.retry()` and recording nothing — the only thing that purges the bin, prunes
  audio and removes expired Drive files fails silently every night. Fix: record it.
- **[data/local/worker/DriveSyncWorker.kt:53-77]** The result ignores `pullTimedOut`, so a pull
  that never completes still reports success and WorkManager's backoff never reflects it. Fix:
  retry when the pull timed out.
- **[data/local/worker/DigestReceiver.kt:75-160]** The morning digest has its own copy of the whole
  pipeline beside `DigestGenerator`, whose header calls itself the single source of truth — and
  they have drifted: `DigestGenerator` honours `notifAiEnabled`, `DigestReceiver` does not, so
  switching AI notifications off still bills for a Claude call every morning. Fix: call the
  generator.
- **[data/local/worker/SmartNotificationWorker.kt · NotificationScheduler.kt:26-67 ·
  DigestScheduler.kt]** Three schedulers and one worker are dead, on a *different* mechanism
  (WorkManager rather than AlarmManager) with near-identical names beside the live ones, and the
  worker carries a second copy of the notification-building block. Fix: delete them, keeping the
  `Slot` enum.
- **[DigestReceiver.kt:45 · SmartNotificationReceiver.kt:31 · BusyMode.kt (BusyModeReceiver)]**
  Three receivers still launch on a bare `CoroutineScope(Dispatchers.IO)` with no handler — the
  pattern removed from `Application.onCreate` and `BootReceiver` for killing the process, and
  `BusyMode.isSuppressing` is called outside any `runCatching`. Fix: use `appScope`.
- **[data/remote/DriveRepository.kt:515-530]** `parseJournalRaw` strips every HTML comment in the
  whole file, body included, so an entry containing one loses that text on each round trip. The
  chat parser documents this hazard and avoids it. Fix: strip only the leading metadata block.
- **[data/remote/DriveRepository.kt:175-214 · ui/screens/settings/SettingsViewModel.kt:262-267]**
  `publishSettingsKeys` never blanks a key and `saveApiKey` refuses a blank, so there is no way to
  revoke a credential from the phone — the web app keeps using it. Fix: an explicit "remove key"
  that writes the blank through.
- **[data/remote/WhisperClient.kt:40-50]** The response is never closed, on the client with a
  ten-minute read timeout — so each transcription holds a socket for up to ten minutes.
  `FirefliesRepository.kt:62-72` fixed exactly this centrally and Whisper was missed. Fix: `use {}`.
- **[data/remote/CalendarRepository.kt:142-149]** `removeCachedEventsForCalendars` does
  `deleteAll()` then `insertAll()` outside a transaction — directly below `replaceAll`, which
  exists because that pair was unsafe. Fix: call `replaceAll`.
- **[data/preferences/UserPreferences.kt — every flow]** No flow applies DataStore's documented
  `.catch { emit(emptyPreferences()) }`, so a corrupt preferences file throws into ~100 collectors
  at once — Compose screens, the sync worker, the alarm receivers and the microphone services.
  Fix: one private `prefsFlow` with the catch.
- **[data/audio/AmbientBuffer.kt:180-215]** `drainTo` holds the ring's monitor for the whole drain
  while its sink is an AAC encode loop, not a copy — at 20 minutes that is tens of seconds, not the
  "well under a second" the comment claims, and the wake-word thread blocks on the same monitor
  throughout. Fix: snapshot the ranges under the lock, encode outside it.
- **[data/local/worker/VoiceCaptureService.kt:698]** `getMemoryMd() ?: INITIAL_MEMORY` — the
  null-means-absent conflation fixed everywhere else, so an unreachable Drive runs the whole voice
  conversation on the seed memory. Fix: `readMemoryMd()` and say so on failure.
- **[data/local/worker/VoiceCaptureService.kt:1120-1131]** The `[CALENDAR:]` handler swallows
  everything and reports nothing, while `[DONE:]` was deliberately made to say what it did. Fix:
  add to `spoken` on failure.
- **[data/local/worker/VoiceCaptureService.kt:1357-1368]** `postSavedNotification` truncates a
  note or to-do id to `Int` for the `PendingIntent` request code; `IdFloor` now seeds those
  sequences from epoch milliseconds, so `FLAG_UPDATE_CURRENT` can point two confirmations at the
  same item. Fix: a monotonic counter, with the id only in the extras.
- **[domain/journal/JournalReminderScheduler.kt:145-156]** The class comment chooses AlarmManager
  because the reminder "needs to land at a specific minute", then uses `setInexactRepeating`. Fix:
  `setExactAndAllowWhileIdle` with a re-arm, or correct the comment.
- **[ui/screens/dashboard/DashboardScreen.kt:421-768]** The whole screen is one
  `Column(verticalScroll)` with `forEach` over every section — nothing is lazy, so every overdue
  to-do is composed and measured on each recomposition, on the screen whose checkbox causes them.
  Fix: a `LazyColumn`.
- **[data/local/worker/ReminderReceiver.kt:84-108]** A note reminder inherits the to-do
  notification's "Mark Done", which calls `markDone` on an id no to-do has and silently does
  nothing. Fix: build the note reminder without the to-do actions.
- **[ui/screens/notes/NoteEditorScreen.kt:330-344]** "Share note" sends the full text through the
  share sheet with no vault confirmation and an unwrapped `startActivity`, while "Copy Drive link"
  directly below it warns. Fix: one confirmation for both, and wrap the chooser.
- **[ui/screens/todos/TodosViewModel.kt:246-248]** `toggleDone` discards the id `markDone` returns
  and nothing on this screen calls `undoDone`, so un-ticking a recurring to-do leaves the spawned
  occurrence as a duplicate — on the app's main to-do surface. Fix: track it and use `undoDone`.
- **[ui/screens/todos/TodoEditorViewModel.kt:399-403 · TodosViewModel.kt:252-257]** Subtask writes
  never touch the parent to-do's `updatedAt`, so a subtask ticked on the phone is never applied on
  the web or a second device. Fix: bump it.
- **[ui/screens/meetings/MeetingDetailViewModel.kt:145-163]** `approveActionItem` resolves the
  bucket name against `getAllBuckets()` — vault included — while the prompt was built from
  non-vault names only. Same fault as the voice marker path, in the file next door. Fix: match
  against `getNonVaultBuckets()`.
- **[ui/screens/meetings/MeetingDetailViewModel.kt:134-191]** `saveTitle`, `persistRemovedItem` and
  `saveTranscriptOnly` bump `updatedAt` and none of them enqueues a Drive upload, so every edit made
  on the meeting detail screen stays on the phone — and the bumped stamp stops the pull correcting
  it. Fix: `enqueueDriveUpload` after each.
- **[ui/screens/meetings/MeetingViewModel.kt:545-581]** `analyzeTranscript` interpolates the whole
  transcript with no bound, so a ninety-minute meeting costs tens of thousands of tokens on every
  retry and a long enough one 400s into ERROR. Fix: cap it and say so in the prompt.
- **[ui/screens/chat/ChatViewModel.kt:661-670]** `speakOnDevice` fires the completion callback
  immediately, so `Speaker` abandons audio focus mid-reply and the car's music resumes over the
  answer — the exact failure the focus handling exists to prevent. Fix: use the
  `UtteranceProgressListener` already installed.
- **[ui/screens/chat/ChatViewModel.kt:135-160 · 270]** `persistMessage` runs on `viewModelScope`,
  so navigating away as a reply lands loses it from the thread and from the Drive file chat sync
  exists to produce. Fix: `appScope`.
- **[ui/screens/chat/ChatViewModel.kt:691-695]** `clearConversation` clears the screen only — the
  rows and the Drive file survive, so the conversation returns on reopen. Fix: delete the messages
  and mark unsynced, or rename the action.
- **[ui/screens/chat/ChatViewModel.kt:380-395]** `parseAndCreateCalendarEvents` swallows
  everything, reports nothing and runs on `viewModelScope`. Fix: report it in the action summary
  and run it on `appScope`.
- **[ui/screens/settings/SettingsViewModel.kt:437-461]** The wake-word switch writes the preference
  without checking `RECORD_AUDIO`; the service stands down and leaves it reading "on". Fix: check
  and request first.
- **[ui/components/VaultPinDialog.kt:105-114 · data/preferences/UserPreferences.kt:200-204]** The
  vault PIN is an unsalted single-round SHA-256 and the dialog has no attempt limit — and per the
  MainActivity item it is also the app lock. Fix: salt and stretch it, six digits, back off after a
  few wrong entries.
- **[webapp/src/lib/recurrence.ts:26-38 · webapp/src/lib/types.ts:19]** The recurrence union omits
  the phone's `CUSTOM:<days>`, so `nextDueDate` returns null and ticking a custom-interval recurring
  to-do on the web silently ends the chain. Fix: parse it.
- **[webapp/src/lib/drive.ts:771-790]** `listMeetingFolders` sets `pageSize: 100` and does not
  paginate, though `listAllFiles` exists in the same file — past a hundred meetings the older ones
  vanish. Fix: use it.
- **[webapp/src/app/api/meetings/process/route.ts:36]** The prompt hardcodes five bucket names
  rather than reading `buckets.json` — the defect `getBucketConfig` was written to remove. It can
  propose a bucket Carl has since marked vault and cannot propose one he created. Fix: read the
  real list, non-vault only.
- **[webapp/src/app/api/drive/memory/route.ts:38-48]** `PUT` passes `""` when the body omits
  `modifiedTime`, and `updateMemory` reads `""` as "skip the check" — so the conflict guard is
  opt-in, and opting out is the default for a caller that forgets. Fix: require it.
- **[webapp/src/app/api/drive/todos/route.ts:118-147]** `getAllTodosRaw` interpolates `folderId`
  without `esc()` and performs three separate dynamic imports plus an unused binding — carried
  forward from pass 1, still present. Fix: escape and hoist.
- **[webapp/src/app/api/drive/journal/route.ts:111-114]** DELETE validates with `Number(...)`
  rather than `validEntityId` — carried forward, still present. Fix: use the shared gate.
- **[webapp/src/app/api/drive/notes/route.ts:78 · components/notes/NoteEditor.tsx:35]** The bucket
  still defaults to "Personal" on save — and `GET` on the same route *withholds* an unknown-bucket
  note precisely because it might be a vault note. The two halves of one route disagree about what
  unknown means. Fix: keep the unknown state, shown as "Unfiled".
- **[webapp/src/app/api/chat/route.ts:82-84]** The "+N more not listed" count uses the raw to-do
  list — done, archived, deleted and vault included — so the number is wrong in both directions and
  discloses how many the vault is hiding. Fix: count the filtered list.
- **[webapp/src/components/meetings/MeetingRecorder.tsx:142-185]** A denied `getUserMedia` is
  swallowed as "we'll still do text-only transcription", but `SpeechRecognition` needs the
  microphone too and its restart is gated on the absent recorder — so the UI records nothing, says
  nothing, and stops after ~60 s. Fix: surface it and refuse to enter the recording state.
- **[webapp/src/components/meetings/MeetingRecorder.tsx:300-309]** The audio upload is fired
  unawaited with an empty catch, called "non-critical", as the recorder closes — on the one part of
  a meeting that cannot be reconstructed. Fix: await, report, and keep the recorder open.
- **[webapp/next.config.ts:3]** No `headers()`, so no CSP, `X-Frame-Options` or `Referrer-Policy` —
  on an app holding a full-`drive` token whose own guards name XSS and CSRF as the threat model.
  Fix: add them.

---

## Low

- **[carlsbrain/build.gradle.kts:30-34 · proguard-rules.pro]** `isMinifyEnabled = false` with four
  keep rules nobody has exercised. Fix: enable and test, or delete both.
- **[AndroidManifest.xml]** `CAMERA` declared with zero usage; `FOREGROUND_SERVICE_SPECIAL_USE`
  declared with no service of that type; `USE_FINGERPRINT` redundant at minSdk 28. Fix: remove.
- **[AndroidManifest.xml · MediaButtonSession.kt:175-179]** `MediaButtonReceiver` is exported with
  no permission, so any app can bring Carl's Brain to the foreground with the mic screen open. It
  only needs to receive an explicit component delivery. Fix: `exported="false"`.
- **[carlsbrain/build.gradle.kts:60-110]** Versions split between the catalog and inline literals,
  including a release-candidate health-connect handling health data. Fix: one place; pin stable.
- **[navigation/AppNavigation.kt:168-179]** A failed `startChatThreadForPrompt` never consumes
  `pendingChatPrompt`, and the effect is keyed on the value so it does not retry — the weekly-review
  notification silently does nothing. Fix: consume where the navigation is attempted.
- **[navigation/AppNavigation.kt:126-166]** The deep links navigate regardless of `isAuthenticated`,
  so an editor loads behind the lock overlay; only the capture route is gated. Fix: hold them.
- **[navigation/AppNavigation.kt:133-145]** Note and to-do deep links lack `launchSingleTop`, unlike
  the meeting one below them, so two taps stack two editors. Fix: add it.
- **[data/local/dao/TodoDao.kt:128-135]** `getUrgentHighTodos` is unlimited while its vault-filtered
  twin is `LIMIT 5`, so opening the vault changes how much of the Dashboard is shown. Fix: match.
- **[data/local/ErrorLog.kt:113-140]** `append` and `trimIfNeeded` are unsynchronised and the trim
  rewrites the whole file, so concurrent failures interleave and one can be lost — in the file whose
  purpose is being the record. Fix: one lock.
- **[data/local/entity/NoteEntity.kt:48-61 · TodoEntity.kt]** `fromDomain` is lossy — it drops
  reminders, tags, pins, `deletedAt` — and nothing calls it. Fix: delete both.
- **[data/local/worker/DriveSyncWorker.kt:825 · 1011]** A to-do with no resolvable bucket is
  published as "Other" and a note as "Personal" — the "unknown must never become a public bucket"
  rule, stated four times on the pull side, inverted on the push. Fix: skip and record.
- **[data/local/worker/DriveSyncWorker.kt:815 · 871 · 946 · 1011]** The bucket list is re-read three
  times in `pushToDrive` and `getBucketById` runs once per note and per entry inside the loops, under
  a budget already observed to run out. Fix: read once, index by id.
- **[data/local/worker/BusyMode.kt:29-40]** Busy mode suppresses the digest, the four slots and the
  weekly review, and not journal template reminders — so the Sunday nudge still fires on an SES job.
  Fix: check `isSuppressing` there too.
- **[data/local/worker/MeetingAudioStore.kt:26-30]** The KDoc names `pruneUploaded` as the pruner;
  no such function exists — it is in `MidnightCleanupWorker.kt:67-78`. Fix: point at it.
- **[data/local/worker/AmbientBufferService.kt:852-886]** `onDestroy` runs `runBlocking` with an
  encoder finish and a Room write on the main thread, unbounded. Fix: `withTimeout` inside it.
- **[data/audio/AmbientBuffer.kt:206-213]** The ring is cleared outside the `runCatching`, so a
  drain that fails part-way discards what it had not yet handed over. Fix: clear only on success.
- **[data/remote/DriveRepository.kt:1057-1095]** `name` and `folderId` are interpolated into the `q`
  string unescaped in six functions; not reachable today, but the web has `escapeDriveQueryValue`
  and `driveFileMetadata` three functions away argues the next person should not have to know. Fix:
  one helper.
- **[data/remote/DriveRepository.kt:407-414 · 1086-1090]** `uploadNoteFile` defaults `bucketName` to
  "Personal" — the same trap in the function that writes the comment — and `findFolderIn` is dead.
  Fix: require the parameter; delete the function.
- **[data/remote/WeatherRepository.kt:24-45]** Raw `HttpURLConnection` outside the app's whole HTTP
  configuration, with the reader unclosed on the exception path. Fix: move onto the shared client.
- **[data/remote/CalendarRepository.kt:171-207]** `createEvent` always posts to `primary`, though
  every read spans all calendars — so "put SES training in the calendar" files it in the wrong one.
  Fix: take a calendar id.
- **[data/health/HealthRepository.kt:250-262]** `writeNutrition` writes a zero-length interval,
  which Health Connect can reject with a generic failure. Fix: give it a nominal duration.
- **[data/local/worker/ReminderScheduler.kt:26-37]** The alarm request code truncates the id to 31
  bits. A collision needs an exact multiple of 2³¹ apart, so it is a remote coincidence — noted only
  because the consequence is silent and severe. Fix: hash rather than truncate if it is touched.
- **[domain/usecase/CompleteTodoUseCase.kt:73-79]** The spawned occurrence copies
  `calendarEventId` and `sourceMeetingId`, so two rows claim the same calendar event and the import
  guard returns whichever it finds. Fix: clear them.
- **[domain/chat/ChatTools.kt:236 · webapp/src/lib/chatTools.ts:245-249]** A failed tool returns
  `err.message` to the model, which can carry a file or bucket name. Fix: a fixed sentence, detail
  to the log — both clients together.
- **[domain/chat/ChatTools.kt:74-82]** `search_todos` is described as "his current to-dos" and
  reports "No outstanding to-dos", but the query has no `isDone = 0` predicate. Fix: filter, or say
  it includes completed items.
- **[domain/loosethread/LooseThreadDetector.kt:79]** `getSubtasksForTodos` binds one parameter per
  active to-do; `getAllSubtasksOnce` exists with a comment saying it was written to avoid this.
  Fix: use it.
- **[ui/screens/dashboard/DashboardViewModel.kt:411-414]** `refreshIfStale` regenerates the briefing
  through a paid call on every resume past fifteen minutes — the cost the September pass removed
  from the completion path. Fix: give the briefing its own longer threshold.
- **[ui/screens/dashboard/DashboardScreen.kt:198-206]** `greetingText` is fixed for the composition,
  so the app left open overnight still says "Good evening". Fix: key it on the hour.
- **[ui/screens/notes/NotesViewModel.kt:38-83]** Every note's full content is held in memory and
  `content.contains` runs across all of it on every keystroke — the one search that does not use the
  DAO. Fix: debounce and use `searchNotes`.
- **[ui/screens/todos/TodosScreen.kt:510 · 533-536]** The right-to-left swipe shows a red delete icon
  and archives. Fix: use the archive icon.
- **[ui/screens/capture/CaptureViewModel.kt:111-134]** A capture fires a bucket-suggestion call and
  then a second classification call on save, discarding the first — two paid calls on the app's
  most frequent action. Fix: reuse the suggestion.
- **[ui/screens/meetings/MeetingDetailScreen.kt:610-678]** Four of five share buttons call
  `startActivity` unguarded; the top-bar share twelve lines earlier wraps its call. Fix: one helper.
- **[ui/screens/meetings/MeetingDetailViewModel.kt:96-102]** A missing meeting leaves `id = 0` and
  renders an editable blank whose Save does nothing; `NoteEditorViewModel` has `isMissing` for
  exactly this. Fix: add the flag.
- **[ui/screens/settings/SettingsViewModel.kt:172-173 · SettingsScreen.kt:1987 · 2013]** The bucket
  vault filter is applied in the composable, twice — the one thing CLAUDE.md says belongs in SQL.
  Fix: a vault-aware flow.
- **[ui/screens/settings/SettingsViewModel.kt:277-292 · 667-673]** `restoreFromDrive` and
  `forceResyncNotes` report success before anything is verified — the "don't assert what you didn't
  check" fault. Fix: report what happened.
- **[ui/screens/search/SearchViewModel.kt:93-99]** The calendar cache is never invalidated, so
  results go stale for the session. Fix: a short TTL.
- **[ui/screens/settings/MemoryEditorViewModel.kt:96-138 · chat/ChatViewModel.kt:100]** A save
  invalidates the shared cache but not Chat's own `memoryMd`, so an open conversation keeps building
  prompts from the pre-edit text. Fix: re-read when the prompt is built.
- **[ui/components/VaultPinDialog.kt:99-102]** SET accepts any four digits, including "1234" — which
  matters only because this PIN is also the app lock. Fix: reject trivial values.
- **[webapp/src/lib/drive.ts:193-238 · 639-682]** Both listings re-download every file on every page
  load; nothing uses `modifiedTime` to skip, though the phone's pull was rewritten to. Fix: cache by
  `(fileId, modifiedTime)`.
- **[webapp/src/lib/auth.ts:41-62]** The web requests full `drive` scope while Android requests
  `drive.file`. The whole of `driveGuards.ts` exists because of it; whether `drive.file` would cover
  the phone's files under the same client id is worth testing rather than assuming. Fix: test, and
  narrow if it can.
- **[webapp/src/components/dashboard/DashboardContent.tsx:196-199 · 295-299]** The effect guards on
  `briefing`, so it never regenerates — yet the comment below the dependency array says unlocking
  the vault regenerates it. Fix: key the guard on the data, or correct the comment.
- **[webapp/src/lib/claude.ts:106-131 · 154-184]** `streamChatResponse` returns early for unleashed
  mode, so every `unleashed ?` conditional below it is dead — and states a different model and token
  ceiling from the live path, so it reads as configuration. `generateBriefing` in the same file is
  unreferenced. Fix: collapse and delete.
- **[webapp/package.json:15]** `marked` is a dependency imported nowhere — and is the one that would
  be an XSS vector if ever wired to `dangerouslySetInnerHTML`, since it has not sanitised since v5.
  Fix: remove it.

---

## What is genuinely good, and should not be tidied away

Recorded because a later pass could easily "simplify" these back into the bugs they fixed.

- The vault rule is in SQL in DAO pairs on the phone and server-side on the web, with a
  `hiddenCount` so the UI can say how many without naming any. The gaps above are the edges nobody
  revisited, not a flawed design.
- `MemoryLearner.mutate` as the single entry point to memory.md, reading fresh under a lock with a
  `modifiedTime` guard, is right — and the reason the remaining web-side hole is the last one.
- `Speaker`'s single-fire callback contract, and its refusal to have a "stop and complete"
  counterpart, is correct and load-bearing.
- `IdFloor`, `TombstoneEntity`, delete-as-stamp on both clients, and the wire-format
  "absent is not empty" rule are all doing real work.
- The per-entry field snapshot on journal entries, and `JournalTrends` reading it rather than the
  live template, is the right call and the reason the web round-trip bug was findable.
- `lib/driveQuery.ts` / `lib/driveGuards.ts` are the right abstraction; the two holes found this
  pass are routes that slipped past them, not a failure of the idea.
