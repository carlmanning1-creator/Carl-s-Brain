# Full review, pass 2 — findings

Accumulated per module. See `REVIEW2_PROGRESS.md` for coverage. Format is the agreed one:
issue / risk / fix, referenced by `file:line`, no code quoted.

Severity is assigned per finding here; the final aggregated report groups them.

---

## A1 — build files, manifest, resources

- **[carlsbrain/build.gradle.kts:20]** Issue: `versionName` is `"2.13"` while CLAUDE.md documents
  the app through 2.21, and `versionCode` 30 has not moved with the last three feature versions.
  Risk: the About screen and any crash report name a version that predates most of the code in
  it, so a bug report cannot be tied to a build. Severity: Medium.
  Fix: bump to `versionCode = 31`, `versionName = "2.21"`, and treat it as part of the release
  step rather than an occasional edit.

- **[carlsbrain/build.gradle.kts:36-52]** Issue: the release `signingConfig` is assigned
  unconditionally, though the surrounding comment says an absent `local.properties` means signing
  is "simply skipped"; the config is created with null keystore properties.
  Risk: `assembleRelease` on a machine without the keystore fails inside the signing task with a
  null-path error rather than producing an unsigned APK as the comment promises. Severity: Medium.
  Fix: build the signing config only when the keystore file exists, and assign
  `signingConfig` inside that same condition.

- **[carlsbrain/build.gradle.kts:30-34 · carlsbrain/proguard-rules.pro:1-40]** Issue:
  `isMinifyEnabled = false` with `proguardFiles` declared and four keep rules maintained.
  Risk: none today, but the keep rules are untested — the day minify is switched on for app size,
  they are the only thing standing between the release build and reflection failures in Room,
  Gson and Retrofit, and nobody has ever exercised them. Severity: Low.
  Fix: either enable minify and test a release build, or delete the rules file and the
  `proguardFiles` line so it does not read as working configuration.

- **[carlsbrain/src/main/AndroidManifest.xml]** Issue: `CAMERA` is declared and nothing in
  `src/` references the camera — attachments are picked through the photo picker.
  Risk: the Play listing asks for a permission the app never uses, and any future contributor
  reads it as evidence the app takes photos. Severity: Low.
  Fix: remove the `CAMERA` permission.

- **[carlsbrain/src/main/AndroidManifest.xml]** Issue: `FOREGROUND_SERVICE_SPECIAL_USE` is
  declared but no service uses `specialUse` as its foreground type; and `USE_FINGERPRINT` is
  declared alongside `USE_BIOMETRIC` though it is deprecated and redundant at `minSdk 28`.
  Risk: cosmetic, but `SPECIAL_USE` in particular draws a Play policy review question that has
  no answer, because there is no special use to declare. Severity: Low.
  Fix: remove both.

- **[carlsbrain/src/main/AndroidManifest.xml · MediaButtonSession.kt:175-179]** Issue:
  `MediaButtonReceiver` is exported with an `ACTION_MEDIA_BUTTON` filter and no permission, and
  its handler starts `MainActivity` with `ACTION_OPEN_CAPTURE_VOICE`.
  Risk: any app on the device can broadcast a synthetic media-button event and bring Carl's Brain
  to the foreground with the microphone screen open. Nuisance rather than capture — the mic needs
  the activity and is visible — but it is an unauthenticated path into a recording surface.
  Severity: Low.
  Fix: the receiver only exists as the framework's fallback target, which is delivered to an
  explicit component; it does not need to be exported to receive that. Set
  `android:exported="false"`.

- **[carlsbrain/schemas/com.carlmanning.carlsbrain.data.local.AppDatabase/]** Issue: exported
  schemas exist for 22, 23, 25, 26, 28 and 29 — 24, 27 and 30 are missing, and 30 is the current
  version.
  Risk: `MigrationTest` can only verify a step whose start and end schemas are both present, so
  migrations 23→24, 26→27 and 29→30 are untested by construction — and 29→30 is the newest and
  least exercised. A bad migration is a wiped database on Carl's phone. Severity: High.
  Fix: build once with `exportSchema` working to regenerate the missing JSON, commit all three,
  and add the missing `MigrationTestHelper` cases.

- **[carlsbrain/build.gradle.kts:60-110]** Issue: dependency versions are split between the
  version catalog and inline literals, including `androidx.health.connect:connect-client:1.1.0-rc01`.
  Risk: a release-candidate library is handling health data on the one path where a breaking
  change lands silently; and two places to look means one gets updated and the other does not.
  Severity: Low.
  Fix: move every inline version into `libs.versions.toml`, and pin health-connect to a stable
  release when one is available.

---

## A2 — app shell: CarlsBrainApp, MainActivity, AppViewModel, navigation

- **[CarlsBrainApp.kt:288-302 · 266-286 · 363-375]** Issue: all three periodic workers are
  enqueued with `ExistingPeriodicWorkPolicy.KEEP`, so the period and constraints are frozen at
  whatever the very first install created.
  Risk: changing the Drive sync interval, its network constraint, or the Fireflies cadence in a
  later version has no effect on Carl's phone — the code says 15 minutes and the device keeps
  running the old schedule, with nothing to indicate the change did not take. Severity: Medium.
  Fix: `UPDATE` for all three. It keeps the existing work's next-run time where the request is
  unchanged and re-applies it where it is not, which is the behaviour the code is assuming.

- **[CarlsBrainApp.kt:119-127]** Issue: the template seeder and journal-reminder rearm are wrapped
  in a bare `runCatching` with no `onFailure`, unlike every other guarded startup step in this
  file, which records to `ErrorLog`.
  Risk: a failed migration or a revoked exact-alarm permission silently stops every journal
  reminder from being armed, and the Diagnostics screen — built precisely for this class of
  invisible background failure — shows nothing. Severity: Medium.
  Fix: `.onFailure { ErrorLog.record("startupSeed", it) }`, and separate the two steps so a
  failed seed does not skip the rearm.

- **[CarlsBrainApp.kt:139-252 · 128-134]** Issue: `createNotificationChannels`,
  `scheduleMidnightCleanup`, `scheduleDriveSync` and `scheduleFirefliesSync` run synchronously
  and unguarded in `onCreate`; `getSystemService` and `WorkManager.getInstance` can both throw.
  Risk: this is the same shape the September pass fixed for the coroutine steps — one throw takes
  the process down during startup, and every later step with it, with the app simply vanishing.
  The guarded steps are the ones that were already known to fail; these are just as fatal.
  Severity: Medium.
  Fix: wrap each of the four, recording to `ErrorLog`, so the list of startup jobs behaves like
  the list of independent jobs it is.

- **[MainActivity.kt:193-208 · 226-231]** Issue: the vault PIN doubles as the whole-app unlock
  when biometric authentication is dismissed.
  Risk: a four-digit PIN intended as the *second* factor on the vault is also the *only* factor
  on the app, and once it opens the app it opens the vault too — so shoulder-surfing it collapses
  both gates at once. The vault is a visibility control rather than a security one, but this is
  still the app's outer lock. Severity: Medium (security).
  Fix: either keep the PIN fallback for the app lock and require biometric-or-nothing for the
  vault once inside, or make it a separate app PIN. Carl's call which.

- **[navigation/AppNavigation.kt:168-179]** Issue: `pendingChatPrompt` is only cleared by
  `ChatScreen`'s `onAutoSendConsumed`; if `startChatThreadForPrompt`'s insert fails, `onCreated`
  never runs, the navigation never happens and the prompt is never consumed.
  Risk: the `LaunchedEffect` is keyed on the value, so it does not retry — the weekly-review
  notification silently does nothing, and stays undone until the app is restarted.
  Severity: Low.
  Fix: consume the prompt in the same place the navigation is attempted, and surface a failure.

- **[navigation/AppNavigation.kt:126-166]** Issue: the deep-link `LaunchedEffect`s navigate
  regardless of `isAuthenticated`, and only the capture route is passed a gate (`canStartVoice`).
  Risk: a notification tap while the app is locked composes the note, to-do or meeting editor
  behind the lock overlay — loading its content, and firing whatever the editor does on open —
  before Carl has authenticated. Nothing is displayed, but work is done on unauthenticated
  input. Severity: Low.
  Fix: hold the pending deep links until `isAuthenticated`, as the capture screen already does.

- **[navigation/AppNavigation.kt:133-145]** Issue: the note and to-do deep links navigate without
  `launchSingleTop`, unlike the meeting one directly below them.
  Risk: two taps on the same reminder stack two identical editors, so backing out of one lands on
  the other. Severity: Low.
  Fix: add `launchSingleTop = true` for consistency with the meeting route.

- **[BootReceiver.kt:105-119]** Issue: the wake word and ambient buffer are restarted from
  `BOOT_COMPLETED` with `startForegroundService`. Android 14+ refuses a `microphone`-type
  foreground service started from a while-in-use exemption such as boot; `step()` records the
  refusal and carries on.
  Risk: on Carl's phone "Hey Brain" is very likely dead after every reboot, silently — the
  toggle in Settings still reads on, the notification is absent, and the only trace is a line in
  Diagnostics nobody thinks to look at. Severity: High.
  Fix: verify against the target device. If it is refused, stop pretending the service restarts:
  post a notification saying the wake word needs re-arming, and start it from `MainActivity` on
  the next foreground launch instead.

- **[BootReceiver.kt:28 · 120-123]** Issue: the `goAsync` lease covers DataStore reads, a Room
  query, an unbounded per-reminder loop and two service starts, all on a device that has just
  booted.
  Risk: `goAsync` gives roughly ten seconds; past that Android may kill the process mid-rearm,
  leaving an arbitrary suffix of the reminders unset with no error at all. Severity: Medium.
  Fix: keep the lease for the ordering work and hand the reminder rebuild to a
  `OneTimeWorkRequest`, which is designed for exactly this and survives the process going away.

---

## A3 — data/local: AppDatabase, IdFloor, ErrorLog, DAOs

- **[ui/screens/settings/SettingsViewModel.kt:936-940 · dao/JournalDao.kt:28-34]** Issue:
  `reassignAll` moves to-dos, notes and meetings off a bucket being deleted, but **not journal
  entries or templates**, which also carry a nullable `bucketId` with no foreign key.
  Risk: deleting a **vault** bucket leaves its journal entries pointing at an id no bucket row
  has. Every journal vault gate is `bucketId NOT IN (SELECT id FROM buckets WHERE isVault = 1)`,
  which an orphaned id satisfies — so those entries become visible with the vault closed, enter
  search, reach Claude, and start plotting points on the Trends charts. Silent, and permanent.
  Severity: **Critical**.
  Fix: reassign `journal_entries.bucketId` and `journal_templates.bucketId` in `reassignAll` too,
  and count journal entries in the pre-deletion dialog — it currently reports a bucket holding
  only journal entries as empty.

- **[dao/MeetingDao.kt:79-88 · 93-94]** Issue: `softDeleteMeeting`, `restoreMeetingFromBin` and
  `setBucket` change what a meeting means without moving `updatedAt`, though the meeting pull is
  gated on exactly that stamp.
  Risk: filing a meeting into a vault bucket on the phone is discarded by the next pull as stale,
  and the meeting silently returns to being visible. Deleting one has the same shape. The rule is
  stated in `TodoDao.kt:72-79` and was applied to to-dos and notes; meetings were missed.
  Severity: High.
  Fix: move `updatedAt` in all three, as `archiveTodo` does.

- **[dao/NoteDao.kt:164-165 · dao/TodoDao.kt:240-241 · dao/NoteDao.kt:170-171 ·
  dao/TodoDao.kt:246-247]** Issue: `moveAllToBucket`, `restoreNoteFromBin` and
  `restoreTodoFromBin` clear `isSynced` but do not move `updatedAt`.
  Risk: same rule, same consequence — a bucket move or a restore from Recently Deleted is
  published with a stamp that predates it, and the other device's `remote <= local` guard throws
  it away. Severity: Medium.
  Fix: take an `updatedAt` parameter and set it, as every other mutating query here does.

- **[dao/JournalTemplateDao.kt:14-15]** Issue: `getTemplates()` has no vault variant, though a
  template carries `bucketId` and `isPrivateByDefault` and the web app withholds one whose
  default bucket is a vault bucket.
  Risk: the template chips on the Journal screen name a vault-bucketed template with the vault
  closed. CLAUDE.md's own rule for buckets is that the *name* is the sensitive part.
  Severity: Medium.
  Fix: add a `getVisibleTemplates()` excluding private-by-default and vault-bucketed rows, and
  pass the vault state to the Journal screen as every other surface does.

- **[AppDatabase.kt:512]** Issue: `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)`.
  Risk: installing an older APK — a rollback, a sideload, a Play staged-rollout revert — drops
  every table, taking with it anything not yet pushed to Drive: unsynced captures, drafts, the
  whole recycle bin, and all loose-thread state, which is never synced at all. Severity: Medium.
  Fix: keep it (the alternative is an unlaunchable app) but make it visible — record that it
  fired, and force a full re-pull plus a `markAll*Unsynced` afterwards.

- **[dao/TodoDao.kt:128-135]** Issue: `getUrgentHighTodos` returns every match while its
  vault-filtered twin `getUrgentHighTodosNonVault` is capped at `LIMIT 5`.
  Risk: a caller that picks between them by vault state gets a list of a different *size*, so
  opening the vault changes how much of the Dashboard is shown for reasons unrelated to the
  vault. Severity: Low.
  Fix: give both the same limit.

- **[ErrorLog.kt:113-119 · 128-140]** Issue: `append` and `trimIfNeeded` are unsynchronised;
  `trimIfNeeded` reads the whole file and rewrites it.
  Risk: two background failures at once interleave, and an append landing during a trim is lost —
  in the one file whose whole purpose is being the record of what went wrong. Severity: Low.
  Fix: synchronise both on a single lock object.

- **[entity/NoteEntity.kt:48-61 · entity/TodoEntity.kt]** Issue: `fromDomain` is a lossy
  round-trip — it drops `reminderAt`, `sortOrder`, `tags`, `isPinned`, `deletedAt` and
  `sourceMeetingId` — and nothing calls it.
  Risk: dead today, a data-loss bug the first time someone reaches for the obvious-looking
  converter on a save path: saving a note through it silently cancels its reminder, unpins it and
  drops its tags. Severity: Low (simplification).
  Fix: delete both `fromDomain` functions. `toDomain` is used and can stay.

---

## A4 — data/local/worker: sync and cleanup

- **[DriveSyncWorker.kt:766 · DriveSyncWorker.kt:378]** Issue: both edit-pulls skip the row when
  the *text* is unchanged — `file.title == local.title && file.content == local.content` for
  notes, `file.content == local.content` for journal entries — and the bucket and privacy fields
  are only applied after that guard.
  Risk: re-filing a note or a journal entry into a **vault** bucket on the laptop, or ticking a
  journal entry Private there, never reaches the phone, because none of those changes the text.
  The item stays fully visible on the device Carl actually uses, and nothing reports the
  divergence. Severity: **Critical**.
  Fix: compare the whole meaningful payload, not just the text — or drop the early return and let
  the `updatedAt` comparison above it decide, which is what it is there for.

- **[FirefliesSyncWorker.kt:189-215 · 196-201]** Issue: every Fireflies sync writes each new
  meeting's title and the first 200 characters of its summary into memory.md, capped at ten.
  Risk: memory.md is prepended to every Claude call on both clients and is never re-filtered. A
  meeting Carl later moves into a vault bucket keeps its title and overview in that section
  indefinitely, so the vault hides the meeting while its content goes on being sent to the API
  in every prompt. It is also the "shadow copy of records in memory.md" shape CLAUDE.md removed
  for to-dos, rebuilt for meetings. Severity: High.
  Fix: drop the auto-section, or re-derive it from the meeting rows each run and exclude
  vault-bucketed ones. The bullets are already regenerated wholesale, so re-deriving is cheap.

- **[MidnightCleanupWorker.kt:110-113]** Issue: the whole worker is one `runCatching` whose
  `onFailure` returns `Result.retry()` and records nothing.
  Risk: this is the only thing that purges the recycle bin, removes expired Drive files, prunes
  meeting audio and clears loose-thread state. If it starts failing — a Drive permission change,
  a corrupt row — it fails silently every night, disk fills, and Diagnostics shows nothing. It is
  the exact class of invisible background failure `ErrorLog` was built for. Severity: Medium.
  Fix: `.onFailure { ErrorLog.record("MidnightCleanupWorker", it) }` before the fold.

- **[DriveSyncWorker.kt:53-77]** Issue: the return value ignores `pullTimedOut` entirely — a run
  whose pull never completes still reports `Result.success()` as long as the push worked.
  Risk: a pull that times out on every run (a large library, a slow connection) is invisible in
  WorkManager's own reporting, so "the laptop's edits never arrive" has no signal other than a
  Diagnostics line. Severity: Medium.
  Fix: return `Result.retry()` when the pull timed out but the push succeeded, so the backoff
  actually reflects that half the sync is not completing.

*(A meeting-deletion finding was drafted here and withdrawn on checking:
`MeetingUploadWorker.kt:88-94` does write `deletedAt` into `meta.json` and the web list filters
on it at `app/api/drive/meetings/route.ts:222`. The path works.)*

- **[DriveSyncWorker.kt:825 · DriveSyncWorker.kt:1011]** Issue: a to-do whose bucket row cannot be
  found is published as `"Other"` and a note as `"Personal"` — a *public* bucket name invented for
  a row whose bucket is unknown.
  Risk: unreachable today because of the foreign key, but it is precisely the "unknown must never
  become a public bucket" rule stated for the pull side four times in this same file, inverted on
  the push. Severity: Low.
  Fix: skip the row and record it, rather than naming a bucket that was not asked for.

- **[DriveSyncWorker.kt:815 · 871 · 946 · 1011]** Issue: the bucket list is re-read from a Flow
  three times in `pushToDrive`, and `getBucketById` is queried once per note and once per journal
  entry inside the upload loops.
  Risk: performance only, but the push runs under a 60-second budget it has already been observed
  to exhaust. Severity: Low (simplification).
  Fix: read the buckets once at the top and index them by id, as the to-do block already does.

---

## A5 — the microphone services

- **[MeetingRecordingService.kt:106-118]** Issue: `onStartCommand` has two early returns —
  `if (isRecording)` and `meetingId == -1L` — before any `startForeground` call, and
  `MeetingViewModel.kt:294` starts it with `startForegroundService`.
  Risk: `ForegroundServiceDidNotStartInTimeException` kills the process. The `isRecording` branch
  is genuinely reachable: tapping Record twice, or the Quick Settings tile while the screen
  button has already started one. This is the rule CLAUDE.md states in as many words, followed by
  `VoiceCaptureService` and `AmbientBufferService`, and not by this one. Severity: **Critical**.
  Fix: call `startForeground` first, unconditionally, then hand it back on the paths that turn
  out to have nothing to do — exactly as `AmbientBufferService.placeholderForeground` does.

- **[VoiceCaptureService.kt:784-788]** Issue: `ERROR_RECOGNIZER_BUSY`, `ERROR_CLIENT`,
  `ERROR_AUDIO` and the `else` branch all re-enter `startServiceSpeechRecognition` on a timer with
  no attempt counter — only `ERROR_NO_MATCH` is counted, and only to two.
  Risk: a persistent recogniser failure (the mic held by another app, the Google speech service
  unavailable offline) loops forever at roughly one attempt a second. `isConversationActive`
  stays true, so the wake word can never restart, the microphone indicator stays lit, and the
  battery drains — with no exit short of killing the app. Severity: High.
  Fix: count consecutive errors of any kind and `endConversation(intentional = false)` past a
  small ceiling, the way the no-match path already does.

- **[VoiceCaptureService.kt:698]** Issue: `sessionMemory = drive.getMemoryMd() ?: INITIAL_MEMORY`
  — the null-means-absent conflation the September pass identified as a theme and fixed
  everywhere else.
  Risk: with Drive unreachable — routine in Dubbo — the whole voice conversation runs on the
  *seed* memory, so Claude answers as though it knows nothing Carl has told it, and nothing
  says why. Read-only, so not destructive, but it is the same reasoning error in the surface he
  uses hands-free. Severity: Medium.
  Fix: `readMemoryMd()` and distinguish failure from absence, as `MemoryLearner.mutate` now does;
  on failure say the memory could not be loaded rather than substituting the seed.

- **[VoiceCaptureService.kt:1120-1131]** Issue: the `[CALENDAR:]` handler wraps parse and create
  in a bare `runCatching` and reports nothing, while `[DONE:]` was deliberately made to say what
  it did.
  Risk: Claude says "I've put it in your calendar", the parse fails or the Calendar API refuses,
  and nothing is created — hands-free, with no screen being watched. The whole reason the
  marker report exists is that Claude cannot know what actually happened. Severity: Medium.
  Fix: add to `spoken` on failure, matching the `[DONE:]` treatment.

- **[VoiceCaptureService.kt:1357-1368]** Issue: `postSavedNotification` builds its `PendingIntent`
  request code as `(itemId + 10_000).toInt()`, truncating a note or to-do id to `Int`.
  Risk: `IdFloor` seeds the note and to-do sequences from epoch milliseconds (~1.7 × 10¹²), so
  those ids overflow `Int` and truncate to an effectively arbitrary value. `FLAG_UPDATE_CURRENT`
  then rewrites the extras of whichever PendingIntent matches, so two voice-capture confirmations
  whose ids agree on the low 32 bits point at the same item — tapping one opens the other.
  Reachable across devices, since the phone and the web app seed from their own clocks.
  (Meeting ids are *not* epoch-seeded, so `AmbientBufferService.notifyRecordingSaved` and
  `MeetingViewModel.fireMeetingReadyNotification` are unaffected.) Severity: Medium.
  Fix: derive the request code from a small monotonic counter and carry the id only in the extras,
  where it stays a `Long`.

- **[AmbientBufferService.kt:852-886]** Issue: `onDestroy` performs `runBlocking` with a Room
  write and an encoder finish on the main thread.
  Risk: a service teardown has roughly ten seconds; an encoder flush plus a database write inside
  `runBlocking` on the main thread is an ANR waiting for a slow moment. The trade is deliberate
  and documented — losing the recording is worse — but it is unbounded. Severity: Low.
  Fix: bound it (`withTimeout` inside the `runBlocking`) so the worst case is a lost row update
  rather than a hung main thread.

- **[VoiceCaptureService.kt:1303]** Issue: `speak` puts the first 80 characters of Claude's spoken
  reply into the persistent foreground notification.
  Risk: that notification is ongoing and renders on the lock screen, outside the biometric gate,
  and it persists until the next `updateNotification`. The reply is built from non-vault data, so
  this is not a vault leak, but it does leave the last thing the assistant said about Carl's
  to-dos legible to anyone who picks up the phone. Severity: Low.
  Fix: show a fixed "Speaking…" instead; the text is being read aloud anyway.

---

## A6 — digest, alarms, receivers, busy mode

- **[ReminderScheduler.kt:26 · 37]** Issue: the alarm's `PendingIntent` request code is
  `(todoId and 0x7FFFFFFF).toInt()` — the low 31 bits of the row id — and to-do ids are now seeded
  from epoch milliseconds by `IdFloor`.
  Risk: two ids colliding needs them to differ by an exact multiple of 2³¹ (~25 days in
  milliseconds), so this is a remote coincidence rather than something Carl will hit — I checked
  the arithmetic before writing it down. It matters only because the consequence is silent and
  severe: `FLAG_UPDATE_CURRENT` would rewrite the other to-do's extras, so one reminder fires
  naming the wrong task and cancelling either cancels both. Severity: Low.
  Fix: if it is ever touched, hash the id into a wider namespace rather than truncating.

- **[DigestReceiver.kt:75-160 · DigestGenerator.kt]** Issue: the morning digest has its **own**
  copy of the whole pipeline — its own prompt, timeouts, to-do query and fallback text — beside
  `DigestGenerator`, whose own header says it is the single source of truth.
  Risk: they have already drifted. `DigestGenerator` honours `prefs.notifAiEnabled`;
  `DigestReceiver` does not, so switching AI notifications off in Settings still makes the 06:30
  digest call the Claude API every morning and bill for it. The timeout texts differ too.
  Severity: Medium.
  Fix: have `DigestReceiver` call `DigestGenerator.generateWithDataOrFallback` with a MORNING
  slot and delete its private pipeline.

- **[SmartNotificationWorker.kt:1-100 · NotificationScheduler.kt:26-67 · DigestScheduler.kt]**
  Issue: three schedulers and one worker are dead. Nothing enqueues `SmartNotificationWorker`
  (`SmartNotificationReceiver` replaced it), nothing calls `NotificationScheduler.scheduleSlot`
  or `cancelSlot`, and `DigestScheduler` is unreferenced entirely.
  Risk: they are not inert — they are live-looking APIs on a *different mechanism* (WorkManager
  rather than AlarmManager), sitting next to the real ones with near-identical names. The worker
  also carries a second copy of the notification-building block, including the AFTERNOON inline
  actions, which has to stay in step with the receiver's and has no reason to. Severity: Medium
  (simplification).
  Fix: delete `DigestScheduler`, `NotificationScheduler.scheduleSlot`/`cancelSlot` and
  `SmartNotificationWorker`'s `doWork`, keeping only the `Slot` enum — move it somewhere that is
  not named "Worker".

- **[DigestReceiver.kt:45 · SmartNotificationReceiver.kt:31 · BusyMode.kt:BusyModeReceiver]**
  Issue: three receivers still launch on a bare `CoroutineScope(Dispatchers.IO)` with no
  `CoroutineExceptionHandler` — the pattern the September pass removed from `Application.onCreate`
  and `BootReceiver` for killing the process.
  Risk: contained today by try/finally plus inner `runCatching`, but the guarding is per-site and
  one uncovered line (`BusyMode.isSuppressing` is called outside any `runCatching`) reaches the
  default handler and kills the process from a background alarm, with no screen and no log.
  Severity: Medium.
  Fix: use `CarlsBrainApp.appScope`, which has the handler and writes to `ErrorLog`.

- **[BusyMode.kt:29-40 · domain/journal/JournalReminderScheduler.kt]** Issue: busy mode suppresses
  the digest, the four smart slots and the weekly review, and says so — but journal template
  reminders are not in the list.
  Risk: the one mode whose purpose is "stop talking to me, I am on an SES job" still fires the
  Sunday training-journal nudge. Severity: Low.
  Fix: check `BusyMode.isSuppressing` in the journal reminder receiver too, re-arming first as
  the others do.

- **[MeetingAudioStore.kt:26-30]** Issue: the KDoc names `pruneUploaded` as the function that
  deletes old recordings; no such function exists — the pruning lives in
  `MidnightCleanupWorker.kt:67-78`.
  Risk: documentation drift on the one file whose subject is "the audio is the only copy".
  Severity: Low.
  Fix: point the comment at the worker.

---

## A7 — data/remote

- **[DriveRepository.kt:1109-1133]** Issue: `listFiles` never paginates. Drive's `files.list`
  returns 100 results by default and there is no `pageSize` or `nextPageToken` handling — so
  `listNoteFiles`, `listJournalFiles`, `listChatFiles`, `listNoteIds` and `listJournalIds` are all
  silently capped at 100. The **web app already paginates** (`webapp/src/lib/drive.ts:136-149`,
  `pageSize: 1000` with a page loop), so the two clients disagree about what is on Drive.
  Risk: two compounding failures. Notes past the first hundred never reach a replacement phone.
  Worse, `DriveSyncWorker.kt:655-657` treats "on Drive?" as `id in driveNoteIds` and marks
  everything absent as a lost upload — so past 100 files, **every note beyond the first page is
  marked unsynced and re-uploaded on every fifteen-minute sync, forever**. That is a permanent
  re-upload storm which starves the push budget and explains a push that never finishes. Same
  shape for journal entries and chat threads. Severity: **Critical**.
  Fix: loop on `nextPageToken` with `pageSize=1000`, exactly as the web app does, and only then
  is the missing-file check safe.

- **[DriveRepository.kt:515-530]** Issue: `parseJournalRaw` builds the entry body with
  `raw.replace(Regex("<!--[\\s\\S]*?-->"), "")` — it strips every HTML comment in the whole file,
  body included.
  Risk: a journal entry containing an HTML comment — a pasted snippet, anything Claude wrote into
  a spoken entry — silently loses that text on every round trip through Drive. The chat parser
  documents this exact hazard at `DriveRepository.kt:582-586` and splits on a delimiter to avoid
  it; the journal parser was not given the same treatment. Severity: Medium.
  Fix: strip only the leading metadata block, as the note parser's `parseNoteContent` does by
  working line-by-line from the top.

- **[DriveRepository.kt:175-214]** Issue: `publishSettingsKeys` merges and never blanks — a blank
  local key always yields the stored one — and `saveApiKeyToSettings` refuses a blank outright.
  Risk: there is no way to revoke an API key from the phone. Clearing it in Settings stops the
  phone using it and leaves it in `settings.json`, where the web app keeps using it indefinitely.
  For a credential that is the wrong default. Severity: Medium (security).
  Fix: keep the merge for a key the phone simply does not have, but give Settings an explicit
  "remove key" that writes the blank through.

- **[DriveRepository.kt:1092-1095 · 1057-1090]** Issue: `name` and `folderId` are interpolated
  into the Drive `q` string with no escaping, in `findFile`, both `getOrCreateFolder` overloads,
  `findFolder`, `findFolderIn` and `findMemoryFile`.
  Risk: not reachable today — every name that gets here is app-generated — but `driveFileMetadata`
  three functions away argues in its own comment that the next person should not have to know the
  rule, and the web app has an `escapeDriveQueryValue` for precisely this. Severity: Low.
  Fix: one `escapeDriveQuery` helper used by every `q` builder here.

- **[DriveRepository.kt:407-414 · 1086-1090]** Issue: `uploadNoteFile` defaults `bucketName` to
  `"Personal"`, and `findFolderIn` is unreferenced.
  Risk: the default is the "unknown must never become a public bucket" trap in the one function
  that writes the bucket comment; the unused function is dead weight. Severity: Low.
  Fix: make `bucketName` a required parameter, and delete `findFolderIn`.

- **[WhisperClient.kt:40-50]** Issue: the response is never closed — no `use {}`, unlike every
  other network call in this package. `FirefliesRepository.kt:62-72` documents that all three of
  its calls had this same leak and fixes it centrally; Whisper was missed.
  Risk: a leaked connection per transcription, on the client with a **ten-minute** read timeout —
  so each leak holds a socket for up to ten minutes. Severity: Medium.
  Fix: `.execute().use { … }`, as `FirefliesRepository.post` does.

- **[CalendarRepository.kt:142-149]** Issue: `removeCachedEventsForCalendars` does `deleteAll()`
  then `insertAll(remaining)` outside a transaction — directly below `replaceAll`, which exists
  in `CalendarEventDao.kt:20-30` precisely because that pair was unsafe.
  Risk: a crash or cancellation between the two leaves no cached calendar at all, which is the
  Dashboard showing an empty day offline with nothing to say it is not really empty — the exact
  failure the `@Transaction` was added for. Severity: Medium.
  Fix: call `replaceAll(remaining)`.

- **[data/remote/WeatherRepository.kt:24-45]** Issue: uses raw `HttpURLConnection` rather than the
  shared OkHttp client, hardcodes Dubbo's coordinates, and never closes the reader.
  Risk: it sits outside the app's whole HTTP configuration — no shared connection pool, no shared
  timeouts, no proxy handling — and a stream left open on the exception path. Minor in isolation;
  it is the one network call in the app that plays by different rules. Severity: Low.
  Fix: move it onto `CarlsBrainApp.httpClient` with `use {}`.

- **[CalendarRepository.kt:171-207]** Issue: `createEvent` always posts to `calendars/primary`,
  though every read path deliberately spans all non-excluded calendars.
  Risk: an event created by voice or by Chat's `[CALENDAR:]` marker always lands on the personal
  calendar, never on SES or a shared one — so "put SES training in the calendar" quietly files it
  in the wrong place. Severity: Low.
  Fix: take a calendar id, defaulting to primary, and let the marker name one.

---

## A8 — data/: audio, voice, health, export, preferences

- **[data/export/BrainExporter.kt]** Issue: the export writes notes, to-dos, meetings, calendar
  events, buckets and memory.md — and contains **no reference to the journal at all**. Chat
  threads and subtasks are missing too.
  Risk: the file's own header calls this "Carl's whole brain… the thing that survives the app
  itself", and the single most personal record in it — every journal entry, its template answers
  and its Trends history — is silently absent. Carl would only discover it by opening the zip
  after he needed it. This is the same shape as journal entries being missing from Recently
  Deleted, which the September pass fixed. Severity: High.
  Fix: add `journal.csv` plus per-entry markdown, honouring both halves of the journal vault rule
  (`isPrivate` and a vault bucket) behind `includeVault`, and add subtasks and chat threads.

- **[data/health/HealthRepository.kt:88-165]** Issue: none of the four `readRecords` calls handles
  `pageToken`. Health Connect returns at most one page (1000 records) per request.
  Risk: a Garmin-bridged phone writes step records in short buckets, so a 30-day window can
  easily exceed a page — and `readSteps` then **gap-fills the missing days with 0** rather than
  leaving them absent. The result is not "no data", it is a confident zero, fed into the Health
  screen and into the health context appended to Claude's voice prompt. Severity: High.
  Fix: loop on `response.pageToken` until it is null, and never gap-fill a day the query did not
  actually cover.

- **[data/preferences/UserPreferences.kt — every flow]** Issue: no flow applies DataStore's
  documented `.catch { if (it is IOException) emit(emptyPreferences()) else throw it }`. Every one
  of the ~100 preference flows is a bare `context.dataStore.data.map { … }`.
  Risk: a corrupted preferences file — a power loss mid-write is the usual cause — throws
  `IOException` into every collector at once. Those collectors are Compose screens, the sync
  worker, the alarm receivers and the microphone services; several read with `.first()` on scopes
  that would take the process down. The app becomes unusable with clearing app data as the only
  route out. Severity: Medium.
  Fix: one private `prefsFlow` with the catch, and build every flow from it.

- **[data/audio/AmbientBuffer.kt:180-215 · AmbientBufferService.kt:551]** Issue: `drainTo` holds
  the ring's monitor for the whole drain, and its sink is `PcmAacEncoder.feed` — an AAC encode
  loop, not a copy. The comment claims "well under a second".
  Risk: at Carl's 20-minute maximum that is 38 MB pushed through MediaCodec with the lock held,
  which is tens of seconds, not under one. The wake-word thread's `AmbientBuffer.feed` blocks on
  the same monitor throughout, stalling the keyword loop and overrunning `AudioRecord`'s internal
  buffer — so the moment he says "start recording" is also the moment the microphone stops
  keeping up. Severity: Medium.
  Fix: snapshot the ring's byte ranges under the lock, release it, then read and encode outside;
  or drain into a temporary file first and encode from that.

- **[data/audio/AmbientBuffer.kt:206-213]** Issue: `writePos` and `filledBytes` are reset outside
  the `runCatching`, so a drain that fails part-way still discards everything.
  Risk: a read error midway through promotion loses the buffered audio that had not yet been
  handed over, on the one path where that audio is the whole point. Severity: Low.
  Fix: only clear the ring on a complete drain; on failure leave what is left for the next try.

- **[data/health/HealthRepository.kt:250-262]** Issue: `writeNutrition` writes a record whose
  `startTime` and `endTime` are the same instant.
  Risk: Health Connect can reject a zero-length interval, and the `runCatching` turns that into a
  generic failure with no indication that the times are the problem. Severity: Low.
  Fix: give it a short nominal duration.

---

## A9 — domain/

- **[domain/journal/JournalReminderScheduler.kt:145-156]** Issue: the class comment says
  AlarmManager was chosen over WorkManager because the reminder "needs to land at a specific
  minute, and WorkManager's batching moves it around" — and then schedules with
  `setInexactRepeating`, which batches for exactly the same reason.
  Risk: the Sunday training nudge can slip by hours under Doze, which is the failure the design
  note says was being avoided. The digest and to-do reminders it claims to match both use
  `setExactAndAllowWhileIdle`. Severity: Medium.
  Fix: either use `setExactAndAllowWhileIdle` with a re-arm in the receiver, as `DigestReceiver`
  does, or correct the comment to say the timing is approximate.

- **[domain/usecase/CompleteTodoUseCase.kt:73-79]** Issue: `spawnNextRecurrence` copies the whole
  entity, `calendarEventId` included, into the next occurrence.
  Risk: two rows now claim the same Google Calendar event, and
  `TodoDao.findByCalendarEventId`/`findAnyByCalendarEventId` — the guard that stops the calendar
  import recreating a to-do — returns whichever it happens to find. Severity: Low.
  Fix: clear `calendarEventId` and `sourceMeetingId` on the spawned row; the new occurrence is a
  new task, not the same one.

- **[domain/chat/ChatTools.kt:236 · 219]** Issue: a failed tool returns `it.message` to the model,
  and so does the calendar branch.
  Risk: an exception message can carry a Drive file name or a bucket name into the conversation.
  Identical to the still-open web finding at `webapp/src/lib/chatTools.ts:245-249`, so fix both
  together. Severity: Low.
  Fix: return a fixed sentence and put the detail in `ErrorLog`.

- **[domain/chat/ChatTools.kt:74-82 · 170-183]** Issue: `search_todos` is described as listing
  "his current to-dos" and reports "No outstanding to-dos" when empty, but `TodoDao.searchTodos`
  has no `isDone = 0` predicate and returns completed ones too.
  Risk: Claude reads finished work back as outstanding — the same class of wrongness the
  `Todo saved:` memory.md shadow list caused, arriving by a different route. Severity: Low.
  Fix: filter `!isDone` for the empty-query case, or say the list includes completed items.

- **[domain/loosethread/LooseThreadDetector.kt:79]** Issue: `getSubtasksForTodos(todos.map { it.id })`
  binds one parameter per active to-do.
  Risk: SQLite's bound-parameter ceiling. `SubtaskDao.getAllSubtasksOnce` exists with a comment
  saying it was written precisely to avoid this in the sync push; the detector still does it.
  Severity: Low.
  Fix: use `getAllSubtasksOnce()` and group in Kotlin, as the push does.

---

## A10 — ui/screens/dashboard

- **[dashboard/DashboardViewModel.kt:519 · 590-611]** Issue: every `loadData()` runs
  `importCalendarEventsTodos` over **every** timed calendar event today and tomorrow, from every
  non-excluded calendar, creating a to-do for each one in the default bucket. It is
  unconditional — no setting, no prompt.
  Risk: shared, birthday, holiday and subscribed calendars all mint to-dos. The briefing prompt
  twenty lines above goes to some length telling Claude those calendars are background noise; the
  importer files them into the to-do list as work. For the user whose stated problem is *deciding
  what to focus on*, silently adding an item per calendar entry is the wrong direction, and
  `singleEvents=true` means every occurrence of a recurring event gets its own. Severity: High.
  Fix: import only from the primary calendar, or gate it on an explicit per-calendar setting —
  and offer it rather than doing it.

- **[dashboard/DashboardScreen.kt:421-768]** Issue: the whole screen is one
  `Column(verticalScroll)`, and overdue, priority, today, tomorrow and the week are all rendered
  with `forEach`. Nothing here is lazy.
  Risk: every to-do in every section is composed and measured on every recomposition — and a
  to-do list with a long overdue backlog is exactly what this user has. The completion checkbox
  changes state on the same screen, so this is the interaction that pays for it. Severity: Medium.
  Fix: a `LazyColumn` with the sections as items, matching what the Todos and Notes screens do.

- **[dashboard/DashboardViewModel.kt:411-414 · DashboardScreen.kt:224-230]** Issue:
  `refreshIfStale` fires on every `ON_RESUME` past fifteen minutes, and `loadData` unconditionally
  regenerates the briefing through a paid Claude call.
  Risk: the September pass removed exactly this cost from the completion path, on the grounds that
  a checkbox should not cost an API call. Returning to the Dashboard from the Todos tab after
  fifteen minutes still does. Severity: Low.
  Fix: separate "refresh the data" from "regenerate the briefing", and give the briefing its own,
  longer staleness threshold.

- **[dashboard/DashboardScreen.kt:198-206]** Issue: `greetingText` is computed inside a bare
  `remember`, so it is fixed for the life of the composition.
  Risk: the app left open overnight still says "Good evening" in the morning — on the screen whose
  first line is a greeting. Severity: Low.
  Fix: derive it from the same one-minute ticker `BusyModeBanner` already uses, or key the
  `remember` on the hour.

---

## A11 — ui/screens/todos + notes

- **[notes/NoteEditorViewModel.kt:394-399 · 466 · data/local/worker/ReminderReceiver.kt:62-70]**
  Issue: a note reminder is scheduled through `ReminderScheduler` under the key
  `noteId + 1_000_000`, and `ReminderReceiver.postIfAllowed` does its vault check by looking that
  key up in the **to-do** table — `getTodoById(...)` returns null, and the code reads null as
  "nothing to check" and posts anyway.
  Risk: a reminder on a note in a **vault bucket** puts the note's title on the lock screen,
  outside both the biometric gate and the vault. This is the leak the September pass closed for
  to-do reminders, still open for notes because they borrow the to-do's alarm channel.
  Severity: **Critical**.
  Fix: carry the entity type in the alarm's extras and check the right table; or give note
  reminders their own receiver.

- **[data/local/worker/ReminderReceiver.kt:84-108]** Issue: the same borrowed alarm means a note
  reminder gets the to-do notification's "Mark Done" and "Snooze 1h" actions. "Mark Done" calls
  `CompleteTodoUseCase.markDone(noteId + 1_000_000)`, which finds no such to-do and returns.
  Risk: a button that visibly does nothing, on a notification, for an app whose whole promise is
  that things do not get lost. Severity: Medium.
  Fix: build the note reminder's notification without the to-do actions.

- **[todos/TodoEditorViewModel.kt:172-196 · TodoEditorScreen.kt:144 · 995-1004]** Issue: the to-do
  editor's "File" button launches `GetContent("*/*")` and routes it to `addAttachment`, which
  calls `drive.uploadPhoto` — a function that names the file `media_<id>_<ts>.jpg` and stores a
  bare id. `NoteEditorViewModel` has a proper `addFile` using `uploadFile` and the
  `file:<name>:<id>` encoding; the to-do editor does not.
  Risk: attaching a PDF to a to-do uploads it to Drive under a `.jpg` name, discards its real
  filename permanently, renders it as a generic icon that is **not clickable**
  (`clickable(enabled = bitmap != null)`), and leaves no way to identify or open it. The file is
  there and effectively lost. Severity: High.
  Fix: give `TodoEditorViewModel` the same `addFile` path the note editor has, and make the
  non-image tile open the Drive file.

- **[notes/NoteEditorScreen.kt:330-344 · 345-356]** Issue: "Copy Drive link" checks
  `isInVaultBucket()` and warns; "Share note" — directly above it, sending the note's full text
  through the system share sheet — does not, and its `startActivity` is not wrapped.
  Risk: a vault note's entire contents leave the app with one tap and no mention of the vault.
  The pass-1 fix hardened the Drive-link path and stopped there — the same "absent at every edge
  nobody revisited" shape. The unwrapped `startActivity` also crashes on a device with no share
  target, which `BusySessionSheet` guards against and this does not. Severity: Medium.
  Fix: route both through the same confirmation, and wrap the chooser.

- **[todos/TodosViewModel.kt:246-248 · TodosScreen.kt:247 · 507 · 548]** Issue: `toggleDone`
  discards the id `CompleteTodoUseCase.markDone` returns, and nothing on this screen calls
  `undoDone`. The Dashboard keeps that id precisely so its Undo can remove the spawned occurrence.
  Risk: tick a recurring to-do off on the Todos screen — by checkbox, by swipe, or in bulk — then
  un-tick it, and the next occurrence it spawned stays behind as a duplicate. Every route on the
  app's main to-do surface has this; the Dashboard's does not. Severity: Medium.
  Fix: track the spawned id here too and go through `undoDone`, as the Dashboard does.

- **[todos/TodoEditorViewModel.kt:399-403 · TodosViewModel.kt:252-257]** Issue: adding, ticking,
  deleting or reordering a subtask writes only the `subtasks` table — the parent to-do's
  `updatedAt` and `isSynced` are untouched.
  Risk: the push rewrites `todos.json` wholesale so the data does reach Drive, but the other
  client's merge is gated on `dto.updatedAt > existing.updatedAt` — so a subtask ticked on the
  phone is never applied on the web app or a second device. Same rule as `archiveTodo`, which was
  fixed for exactly this reason. Severity: Medium.
  Fix: bump the parent's `updatedAt` and clear `isSynced` on every subtask write.

- **[notes/NotesViewModel.kt:38-44 · 66-83]** Issue: `allNotes` holds every note — full content —
  in memory, and the search filter runs `content.contains(query)` across all of it on every
  keystroke, on the main-safe flow combine.
  Risk: linear in total note bytes per character typed. It is the one screen whose search is not
  the SQL `searchNotes` query the rest of the app uses. Severity: Low.
  Fix: debounce, and use the DAO's search for a non-blank query.

- **[todos/TodosScreen.kt:510 · 533-536]** Issue: swiping a to-do right-to-left shows a red
  background with a **delete** icon and then archives it; the snackbar says "Archived".
  Risk: the gesture promises deletion and does something else. Severity: Low.
  Fix: use the archive icon.

---

## A12 — ui/screens/meetings + capture

- **[res/xml/file_provider_paths.xml:3 · meetings/MeetingDetailScreen.kt:668-678 ·
  data/local/worker/MeetingAudioStore.kt:37-38]** Issue: the FileProvider declares exactly one
  root — `<cache-path name="meetings" path="meetings/" />` — while meeting audio was moved out of
  `cacheDir` into `filesDir` (`MeetingAudioStore.dir` = `File(context.filesDir, "meetings")`).
  `getUriForFile` on a path outside every declared root throws `IllegalArgumentException`, and
  this call is not wrapped.
  Risk: tapping **Share → Audio** on any meeting crashes the app outright. The move to `filesDir`
  was made so a queued recording could not be reclaimed by the system; the provider config was
  not moved with it, and nothing else in the app uses this provider so nothing else surfaced it.
  Severity: **Critical**.
  Fix: add `<files-path name="meetings" path="meetings/" />` (keep the cache root for any legacy
  file), and wrap the `startActivity` as the top-bar share at line 268 already does.

- **[meetings/MeetingDetailScreen.kt:610-616 · 631-637 · 644-650 · 673-678]** Issue: four of the
  five share buttons call `context.startActivity(createChooser(...))` unguarded; the top-bar
  share twelve lines earlier wraps its call and shows a Toast on failure.
  Risk: `ActivityNotFoundException` on a device with no matching share target takes the app down.
  Severity: Low.
  Fix: one shared helper doing the `runCatching` + Toast, used by all five.

- **[meetings/MeetingDetailViewModel.kt:145-163]** Issue: `approveActionItem` resolves the action
  item's bucket name against `getAllBuckets()` — **vault buckets included** — while
  `MeetingViewModel.analyzeTranscript:547` builds the prompt from `getNonVaultBuckets()`.
  Risk: exactly the fault the September pass fixed in the voice marker path, in the file next
  door: the names Claude is offered and the names it can file into do not match, so an item can
  land in a bucket the surface that created it can never see again. Severity: Medium.
  Fix: match against `getNonVaultBuckets()`, as `parseAndActOnMarkers` now does.

- **[meetings/MeetingDetailViewModel.kt:134-143 · 169-181 · 183-191]** Issue: `saveTitle`,
  `persistRemovedItem` (approve/reject) and `saveTranscriptOnly` all bump the meeting's
  `updatedAt` and none of them calls `enqueueDriveUpload` — only delete and the analysis path do.
  Risk: every edit made on the phone's meeting detail screen — renaming it, approving an action
  item, correcting the transcript — stays on the phone. `actions.json` on Drive keeps listing the
  item Carl approved, so the web app goes on showing it pending, and the bumped local `updatedAt`
  guarantees the next pull will not correct the divergence either. Severity: Medium.
  Fix: `enqueueDriveUpload(id)` after each, as the delete path does.

- **[meetings/MeetingViewModel.kt:545-581]** Issue: `analyzeTranscript` interpolates the entire
  transcript into the prompt with no length bound.
  Risk: a ninety-minute meeting is tens of thousands of tokens on every analysis and every retry,
  on the paid API, and a long enough one simply 400s and lands the meeting at ERROR with the
  model's message as the explanation. Severity: Medium.
  Fix: cap the transcript and say in the prompt that it was truncated — the same treatment
  `MemoryPrompt` gives memory.md.

- **[meetings/MeetingDetailViewModel.kt:96-102]** Issue: when the meeting id no longer resolves,
  `loadMeeting` only clears `isLoading`, leaving `id = 0` and every field blank.
  Risk: the screen renders an editable blank meeting whose Save silently does nothing.
  `NoteEditorViewModel` has an `isMissing` flag for precisely this and says so in its KDoc; the
  meeting detail was not given one. Severity: Low.
  Fix: add the same flag and an explanation.

- **[capture/CaptureViewModel.kt:111-134 · 352 · 388]** Issue: a capture over 30 characters fires
  a Claude bucket-suggestion call 1.5 s after typing stops, and `save()` then fires a *second*
  Claude call to classify the same text — the suggestion's answer is discarded.
  Risk: two paid calls per capture where one would do, on the app's highest-frequency action.
  Severity: Low (simplification).
  Fix: reuse the suggestion when one has already landed for the same text.

---

## A13 — ui/screens/journal + chat

- **[chat/ChatViewModel.kt:726-753 · 878-884 · dao/MeetingDao.kt:63-64]** Issue:
  `loadRecentMeetings` calls `getRecentDoneMeetings(5)` — the one meeting query with **no vault
  variant** — and puts each meeting's title, summary, outstanding action items and a transcript
  excerpt straight into the Chat system prompt.
  Risk: Chat is documented, repeatedly and in this same file, as unconditionally vault-closed: its
  buckets, its to-do list and all four of its tools use non-vault queries. This one does not, so
  the contents of a vault-bucketed meeting are sent to the Anthropic API on **every** Chat message
  and are available for Claude to quote back with the vault shut. Severity: **Critical**.
  Fix: add `getRecentDoneNonVaultMeetings` alongside the existing non-vault pair in `MeetingDao`
  and use it here, unconditionally.

- **[chat/ChatViewModel.kt:263-265 · 365-378]** Issue: `sendMessage` runs `parseAndCreateTodos`
  and then `parseAndCompleteTodos` on the same reply, and the completion path has neither the
  exact-match preference, the ambiguity refusal, nor the "exclude to-dos created by this reply"
  guard that `VoiceCaptureService.parseAndActOnMarkers:1018-1099` was given for exactly these
  reasons.
  Risk: a to-do Chat creates in a reply is immediately a candidate for `[DONE:]` in that same
  reply, and an ambiguous title silently completes whichever fuzzy match sorts first instead of
  asking. The voice path's comment spells out the consequence — the item is archived that night
  and vanishes as though it were never created. Severity: High.
  Fix: lift the voice path's `createdTodoIds` set, exact-match preference and ambiguity handling
  into a shared helper both surfaces call.

- **[chat/ChatViewModel.kt:661-670 · domain/chat/Speaker.kt:270-277]** Issue: `speakOnDevice`
  calls `onDone()` immediately after `tts.speak(...)` rather than on utterance completion, so
  `Speaker.complete` runs — and `abandonFocus()` with it — while the device engine is still
  talking.
  Risk: audio focus is handed back mid-reply, so in the car the music resumes over the top of the
  answer. That is precisely the failure `Speaker`'s focus handling exists to prevent, and it is
  defeated by the one caller that does not wire the callback up. Severity: Medium.
  Fix: use the `UtteranceProgressListener` already installed in `initTts` to fire the callback,
  as `VoiceCaptureService.speakOnDevice` does with `pendingTtsOnDone`.

- **[chat/ChatViewModel.kt:135-160 · 270]** Issue: `persistMessage` runs on `viewModelScope`, and
  the reply is persisted from inside `sendMessage`'s `viewModelScope.launch`.
  Risk: navigating away as a reply lands cancels the write, so the answer is on screen for a
  moment and then absent from the thread — and from the Drive file the whole chat-sync feature
  exists to produce. Every editor in the app moved its exit-path save to `appScope` for this
  reason; Chat did not. Severity: Medium.
  Fix: persist on `CarlsBrainApp.appScope`.

- **[chat/ChatViewModel.kt:691-695]** Issue: `clearConversation` clears `apiHistory` and the
  on-screen list and nothing else — the rows stay in `chat_messages` and the thread's Drive file
  is untouched.
  Risk: the conversation reappears in full the next time the thread is opened, or on any other
  device. A control labelled "clear" that clears only the screen is worse than none.
  Severity: Medium.
  Fix: delete the thread's messages and mark it unsynced, or rename the action to "start a new
  thread" and create one.

- **[chat/ChatViewModel.kt:380-395]** Issue: `parseAndCreateCalendarEvents` wraps parse-and-create
  in a bare `runCatching`, reports nothing, and its `createEvent` runs on `viewModelScope`.
  Risk: the same silent failure as the voice `[CALENDAR:]` path — Claude says it added the event,
  nothing happened, and leaving the screen can cancel it besides. Severity: Medium.
  Fix: report the outcome in the message's action summary, alongside the created to-dos and notes
  that already are, and run it on `appScope`.

- **[journal/JournalViewModel.kt:80 · journal/TemplateManagerViewModel.kt:38]** Issue: both read
  `journalTemplateDao.getTemplates()`, which has no vault filter — corroborating the DAO finding
  in A3. The Journal screen's template chips and the manager list both name every template.
  Risk: a template that is private-by-default, or whose default bucket is a vault bucket, has its
  **name** on screen with the vault closed. The web app withholds exactly these; the phone does
  not. Severity: Medium.
  Fix: as in A3 — add the filtered query and pass the vault state to both screens.

---

## A14 — ui/screens/settings + health + calendar + search + onboarding

- **[settings/SettingsViewModel.kt:437-461 · VoiceCaptureService.kt:266-273]** Issue: the wake-word
  switch writes the preference and starts the service without checking `RECORD_AUDIO`. The service
  correctly stands down when the permission is missing — and leaves the preference **on**.
  Risk: the Settings switch reads "on" while "Hey Brain" is dead, with the only trace a `Log.w`.
  Given the wake word is the frictionless-capture path the whole design rests on, a switch that
  lies about it is the worst place for this. Severity: Medium.
  Fix: check the permission before setting the preference, request it, and leave the switch off
  if it is refused.

- **[settings/SettingsViewModel.kt:172-173 · SettingsScreen.kt:1987 · 2013]** Issue:
  `SettingsViewModel.buckets` reads `getAllBuckets()` and the vault filter is applied in the
  composable (`buckets.filter { isVaultVisible || !it.isVault }`), twice.
  Risk: correct today, but it is UI-level filtering of vault names — the one thing CLAUDE.md says
  must live in SQL, "because a screen that forgets to filter is exactly how this project has
  leaked before". Two call sites already, and the vault bucket names are in the composition
  regardless. Severity: Low.
  Fix: expose a vault-aware flow from the ViewModel, as every other screen does.

- **[settings/SettingsViewModel.kt:277-292 · 667-673]** Issue: `restoreFromDrive` reports
  `"Restored: API key, notes & todos syncing"` whether or not the key was read, and
  `forceResyncNotes` reports success the moment the work is *enqueued*.
  Risk: the same "don't assert what you didn't manage to check" fault the timed-out digest was
  fixed for — an offline restore says it worked. Severity: Low.
  Fix: report only what actually happened, and say when the key could not be read.

- **[settings/SettingsViewModel.kt:262-267]** Issue: `saveApiKey` publishes to Drive only when the
  key is non-blank, so clearing it locally leaves it in `settings.json`. Same defect as
  `DriveRepository.publishSettingsKeys` in A7 — recorded here because Settings is where Carl
  would try to do it.
  Risk: no way to revoke a credential from the phone. Severity: Medium (security) — see A7.
  Fix: as in A7.

- **[search/SearchViewModel.kt:93-99]** Issue: `cachedCalendarEvents` is filled on the first search
  and never invalidated for the life of the ViewModel.
  Risk: calendar results go stale for the whole session; an event created or moved after the first
  search is invisible to it. Severity: Low.
  Fix: give the cache a short TTL, as `HealthRepository.isCacheStale` does.

- **[settings/MemoryEditorViewModel.kt:96-138 · chat/ChatViewModel.kt:100]** Issue: a successful
  save calls `MemoryLearner.invalidateCache()`, but `ChatViewModel` holds its own `memoryMd`
  field loaded when the screen opened and nothing invalidates it.
  Risk: editing memory.md while a Chat screen is alive leaves that conversation building prompts
  from the pre-edit text — including any fact Carl just deleted. Severity: Low.
  Fix: re-read `memoryMd` when Chat next builds a prompt, or expose the shared cache as a flow.

---

## A15 — ui/components, tile, widget, util, VoiceCaptureActivity

- **[data/preferences/UserPreferences.kt:200-204 · ui/components/VaultPinDialog.kt:105-114 ·
  MainActivity.kt:193-231]** Issue: the vault PIN is stored as a single-round **unsalted**
  SHA-256, the ENTER dialog has no attempt limit or backoff, and — per A2 — the same PIN is the
  fallback that unlocks the whole app when biometrics are dismissed.
  Risk: a four-digit PIN has ten thousand possible values, so the stored hash is reversible by
  lookup and the dialog can be guessed at without limit. Both matter only to someone holding the
  unlocked-bootloader device, which is a modest threat model — but this is the app's outer lock,
  not just the vault toggle, and the fix is small. Severity: Medium (security).
  Fix: salt and stretch the hash (PBKDF2 or Argon2 via Jetpack Security), require six digits, and
  add an increasing delay after a few wrong entries.

- **[ui/components/VaultPinDialog.kt:99-102]** Issue: SET/CHANGE accepts any PIN of four or more
  digits, with no check against trivial values.
  Risk: minor on its own, and only worth stating because of the point above — this PIN is the app
  lock, so "1234" is the whole gate. Severity: Low.
  Fix: reject an obviously sequential or repeated PIN.

*(`VoiceCaptureActivity`, both Quick Settings tiles, both widgets and `util/` were read and no
further issues found. The Dashboard widget's vault handling in particular is correct: every list
uses a non-vault DAO query and the cached briefing can only have been written with the vault
closed. `VoiceCaptureActivity` owns `isConversationActive` symmetrically across
`onResume`/`onPause`, so the flag cannot latch.)*

---

## W1 + W2 — webapp lib

- **[lib/driveGuards.ts:120-131 · app/api/drive/share/route.ts:43-49]** Issue: `fileIsShareable`
  routes **any** file whose parent is not the SecondBrain root through `meetingFolderIsVisible`,
  on the assumption that a non-root parent is a meeting folder. The `media/` folder — where every
  photo and file attachment lives — is also a child of the root, and it has no `meta.json`, so
  `meetingFolderBucket` returns `""` ("unsorted") and the guard returns **true**.
  Risk: `POST /api/drive/share` takes an arbitrary `fileId` and publishes it to "anyone with the
  link", which is not reversible in practice. So any attachment id — including a photo on a
  vault-bucketed note or on a private journal entry — is shareable **with the vault closed**.
  Reaching it needs an id, and attachment ids do reach the browser (`app/api/drive/journal/route.ts:88`
  returns them), so an id captured while the vault was open keeps working after it is locked —
  precisely the failure this file was written to close, reproduced one folder across.
  Severity: **Critical**.
  Fix: identify the folder by name rather than by "not the root" — only a child of `meetings/`
  gets the meeting rule; anything under `media/` must resolve the owning note or entry's bucket,
  and be refused when it cannot.

- **[lib/recurrence.ts:16-40 · domain/usecase/CompleteTodoUseCase.kt:104-118]** Issue:
  `nextDueDate` steps exactly one interval from the old due date. The phone's `nextDateMs` steps
  **until the date is in the future**, and its comment explains why at length: completing a
  weekly to-do three weeks overdue otherwise produces one that is already two weeks overdue,
  "over and over", so a task he had fallen behind on could never be caught up.
  Risk: this file's own header says it "deliberately mirrors CompleteTodoUseCase" and that two
  implementations which disagree "fail silently". They disagree. Ticking an overdue recurring
  to-do on the laptop spawns an already-overdue occurrence. Severity: High.
  Fix: port the catch-up loop and its bound.

- **[lib/recurrence.ts:26-38 · lib/types.ts:19]** Issue: the recurrence union covers
  DAILY/WEEKLY/FORTNIGHTLY/MONTHLY only; the phone also stores `"CUSTOM:<days>"`. `nextDueDate`
  falls to `default: return null`, so `spawnNextOccurrence` returns null.
  Risk: ticking a custom-interval recurring to-do off on the web silently ends the chain — the
  exact bug this module was written to fix, still open for one of the five recurrence kinds.
  Severity: Medium.
  Fix: parse `CUSTOM:<n>` and add `n` days.

- **[lib/drive.ts:652-677 · 900-915 · vs 164-183]** Issue: `getNotes` and `getChatThreads` still
  fetch every file in one unbounded `Promise.all` and drop a failure with a bare `return null` —
  no concurrency bound, no count of what could not be read. The journal loader twenty lines above
  has both, with a comment explaining that Drive answers a few hundred simultaneous requests with
  403 `userRateLimitExceeded` and that silently dropped items "read exactly like data loss".
  Risk: the same defect, in the same file, with the fix applied to one of three callers. Notes is
  the larger library of the two. Severity: High.
  Fix: use `mapWithConcurrency` and return an `unreadable` count for both, as the journal does.

- **[lib/drive.ts:771-790]** Issue: `listMeetingFolders` sets `pageSize: 100` and does not follow
  `nextPageToken`, though `listAllFiles` exists in the same file for exactly this.
  Risk: past a hundred meetings the older ones simply stop appearing in the web app, with nothing
  to say they were truncated — the same failure the note and journal listings were fixed for.
  Severity: Medium.
  Fix: use `listAllFiles`.

- **[lib/drive.ts:193-238 · 639-682]** Issue: both listings re-download the full content of every
  note and journal entry on every page load; nothing uses Drive's `modifiedTime` to skip a file
  that cannot have changed, though the phone's pull was rewritten to do precisely that.
  Risk: a few hundred entries is a few hundred Drive round trips per page view, which is what
  pushed the phone's sync past its timeout before the same fix was applied there. Severity: Low.
  Fix: cache by `(fileId, modifiedTime)` for the request, or at least short-circuit unchanged
  files across requests.

- **[lib/auth.ts:41-62]** Issue: the web app requests full `https://www.googleapis.com/auth/drive`
  while the Android client requests only `drive.file`.
  Risk: the whole of `lib/driveGuards.ts` exists because of this scope — its header says so. If
  the two clients share an OAuth client id, `drive.file` would cover the files the phone created
  and would make the entire "a route can be pointed at any file in Carl's Drive" class impossible
  rather than guarded. Worth checking rather than assuming; I could not settle it from the code.
  Severity: Low (security, needs verification).
  Fix: test whether `drive.file` can read the phone's files under the same client id; narrow if
  it can.

---

## W3 — webapp API routes

- **[app/api/meetings/process/route.ts:63]** Issue: the action-item regex is
  `/\[?\s*ACTION:\s*([^|\]\n]+?)\s*\|\s*([^\]\n]+?)\s*\]?/gi` — closing bracket optional, bucket
  group lazy. On `[ACTION: Call John | Work]` the engine takes the shortest bucket that lets the
  match succeed: a single `W`.
  Risk: this is the exact bug documented at length in `MeetingViewModel.kt:61-71`, where the fix
  was to make the bracket required. Every action item a meeting processed on the *web* produces
  is filed under a bucket called "W", which matches nothing and falls back to the default — and
  `ork]` is left in the summary. `app/api/drive/meetings/route.ts:17` has the corrected form, so
  the two regexes in this app disagree. Severity: High.
  Fix: use the same `ACTION_REGEX` as the meetings route; export it rather than copying it.

- **[app/api/meetings/process/route.ts:36]** Issue: the prompt hardcodes
  `Buckets must be one of: SES, Family, Work, Personal, Other`.
  Risk: the whole of `getBucketConfig` exists because a hardcoded list here meant a bucket Carl
  created on the phone never appeared and a bucket he marked vault was treated as ordinary. This
  route kept its copy — so it can propose an item for a bucket he has since marked vault, and
  cannot propose one for a bucket he created. Severity: Medium.
  Fix: read `getVaultBucketNames`/`getBucketConfig` and offer only the non-vault names, as
  `MeetingViewModel.analyzeTranscript` does.

- **[app/api/drive/meetings/route.ts:189-217 · lib/drive.ts:422-444]** Issue: the meetings list
  fans out one unbounded `Promise.all` over every folder, each doing four `readFileFromFolder`
  calls — and `readFileByName` has no try/catch, so a single failed read **rejects the whole
  `Promise.all`**.
  Risk: eight Drive round trips per meeting, all at once. One rate-limited response and the route
  returns 500 and the Meetings page shows nothing at all. The notes and journal loaders were both
  given per-file catches for exactly this; meetings gets neither that nor a concurrency bound.
  Severity: High.
  Fix: bound the concurrency with `mapWithConcurrency`, catch per folder, and report a count of
  meetings that could not be read.

- **[app/api/drive/todos/route.ts:52-116]** Issue: `POST` merges the incoming to-do onto the
  stored row and returns `saved` — the **merged row** — with no vault check anywhere in the
  handler.
  Risk: `GET` is carefully vault-filtered server-side and publishes a `hiddenCount` so the UI can
  say how many exist without naming them. `POST` hands the full row back: a request naming a
  vault to-do's id echoes its title, bucket and due date into the response with the vault closed.
  It can also move a to-do into or out of a vault bucket. Severity: High.
  Fix: refuse (404) when the stored row is in a vault bucket and the vault is not open, and
  refuse a `bucket` that names one.

- **[app/api/drive/todos/route.ts:56-58 · 84-96]** Issue: `incoming.id` is never validated —
  unlike the notes and chat routes, which run it through `validEntityId`.
  Risk: a non-numeric id is written straight into `todos.json`, and the phone parses that file
  with `decodeFromString<List<TodoSyncDto>>` where `id` is a `Long`. One bad row throws, the
  merge's `.getOrElse { return }` swallows it, and **the phone's entire to-do sync silently stops
  working** — permanently, since nothing rewrites the file to remove it. Severity: High.
  Fix: `validEntityId` on the way in, as the sibling routes do.

- **[app/api/drive/memory/route.ts:38-48 · lib/drive.ts:596-608]** Issue: `PUT` passes `""` when
  the body omits `modifiedTime`, and `updateMemory` treats `""` as "skip the check".
  Risk: the conflict guard — the thing standing between a laptop edit and everything the phone
  learned since the page loaded — is opt-in at the route boundary, and opting out is the default
  for any caller that forgets the field. `""` legitimately means "there was no file", so the two
  cases are conflated exactly as `getMemoryMd`'s null was. Severity: Medium.
  Fix: require `modifiedTime` to be present (allowing an explicit empty string only when the GET
  reported one), and 400 otherwise.

### Carried forward from `REVIEW_WEBAPP.md`, re-verified as still present

- **[app/api/drive/todos/route.ts:118-147]** `getAllTodosRaw` still interpolates `folderId`
  without `esc()` and still performs three separate `await import("googleapis")` calls plus one
  unused binding. Severity: Medium.
- **[app/api/drive/journal/route.ts:111-114]** DELETE still validates with `Number(...)` rather
  than `validEntityId`. Severity: Medium.
- **[app/api/drive/notes/route.ts:78 · components/notes/NoteEditor.tsx:35]** The bucket still
  falls back to `"Personal"` on save. Worth restating with what this pass found: `GET` on the
  same route **withholds** an unknown-bucket note precisely because it might be a vault note —
  and `POST` relabels that same note into a public bucket. The two halves of one route disagree
  about what unknown means. Severity: Medium.
- **[lib/chatTools.ts:245-249]** A failed tool still returns `err.message` to the model.
  Severity: Low — pair with the identical Android finding in A9.
