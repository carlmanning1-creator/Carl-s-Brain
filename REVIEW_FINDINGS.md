# Review findings (accumulating)

Raw log, in the order found. The final report groups these by severity.

## Module 1 — App shell

- **[CarlsBrainApp.kt:261,305,317,328,378]** Issue: five ad-hoc `CoroutineScope(Dispatchers.IO)` blocks in `onCreate` with no `CoroutineExceptionHandler`, unlike `appScope`.
  Risk: any throw (DataStore read failure, `SecurityException` from AlarmManager, service-start refusal) reaches the default handler and kills the process during startup — the exact silent-crash shape just fixed for `appScope`.
  Fix: run all of these on `appScope`, which already logs to `ErrorLog`.

- **[CarlsBrainApp.kt:381]** Issue: `startForegroundService` is called from `Application.onCreate`, which also runs when the process is started by a widget update, WorkManager or a broadcast — i.e. with no foreground.
  Risk: `ForegroundServiceStartNotAllowedException` on Android 12+, uncaught (see above) → process death, and the wake word stays dead.
  Fix: wrap in `runCatching`, and only start when the app is actually foregrounded (or defer to `MainActivity`).

- **[CarlsBrainApp.kt:98-108]** Issue: `ErrorLog.install` is documented as "first, before anything else can fail" but runs after `OkHttpClient` and `UserPreferences` construction.
  Risk: a startup crash in either is the one class of crash with no other record — precisely what the log exists for.
  Fix: move `ErrorLog.install(this)` to the first statement after `super.onCreate()`.

- **[MainActivity.kt:75,266]** Issue: `isAuthenticated` is persisted in `savedInstanceState`, which survives process death, not just rotation.
  Risk: after the system reclaims the process, the app restores already-unlocked — the biometric gate is skipped on a lock Carl believes is unconditional.
  Fix: keep the rotation behaviour via a retained/config-change signal (e.g. `isChangingConfigurations`) rather than the saved-state bundle.

- **[MainActivity.kt:78]** Issue: `handleIntent(intent)` runs on every `onCreate`, including Activity recreation from a configuration change, and the intent is never cleared.
  Risk: rotating (or a theme/locale change) re-fires `ACTION_OPEN_CAPTURE` / `ACTION_START_MEETING`, reopening capture or restarting a meeting unasked.
  Fix: only handle the intent when `savedInstanceState == null`, or clear the action once consumed.

- **[BootReceiver.kt:30]** Issue: raw `CoroutineScope(Dispatchers.IO)` with no exception handler, holding a `goAsync` lease.
  Risk: a throw — most plausibly `SecurityException` from an exact alarm when the permission has been revoked — crashes the process on every boot, and to-do reminders after the failure point are never rearmed.
  Fix: wrap the body in `runCatching`, or per-scheduler, so one failed alarm does not stop the rest.

- **[AppViewModel.kt:144-158]** Issue: `syncNow` sets `_isSyncing = true` then waits on the work info flow with no timeout.
  Risk: if the constraint is never met (offline) the spinner stays on indefinitely and the manual sync button reads as hung.
  Fix: bound the wait (`withTimeoutOrNull`) and clear the flag in a `finally`.

- **[AppViewModel.kt:33]** Issue: the nav-bar urgent badge counts `getActiveTodos()`, which is not the vault-filtered variant.
  Risk: with the vault closed the badge still counts vault to-dos, so the count disagrees with the list and hints at hidden items.
  Fix: count from the non-vault DAO variant, as every other surface does.

- **[MainActivity.kt:53 / AppNavigation.kt:87]** Issue: `UserPreferences` is constructed per-call site alongside the singleton on `CarlsBrainApp`.
  Risk: duplicated object graph; if the DataStore is not created via a single top-level delegate this throws "multiple DataStores active for the same file". (Confirm in module 11.)
  Fix: use `CarlsBrainApp.userPreferences` everywhere.

## Modules 2-4 — database, entities, DAOs

- **[RecentlyViewedDao.kt:40-70]** Issue: `getRecent` has no vault filter, though `recently_viewed` stores `bucketId`.
  Risk: a vault note or to-do viewed while the vault was open keeps its title on the Dashboard strip after the vault is re-locked — the one surface Carl looks at with the vault closed.
  Fix: add the `bucketId NOT IN (SELECT id FROM buckets WHERE isVault = 1)` clause, or a vault-open/closed pair as every other DAO has.

- **[RecentlyDeletedViewModel.kt:38-47]** Issue: Recently Deleted is built from `getDeletedNotes` / `getDeletedTodos` / `getDeletedMeetings`, none of which is vault-filtered, and `isVaultVisible` is passed to the screen only for the top bar.
  Risk: every deleted vault item's title is listed with the vault closed, for 90 days.
  Fix: vault-filtered DAO variants selected on `isVaultVisible`, as the search screens do.

- **[RecentlyDeletedViewModel.kt:38-47]** Issue: soft-deleted journal entries never appear in Recently Deleted, although `JournalDao.getDeletedEntries` exists and `MidnightCleanupWorker` purges them at 90 days.
  Risk: a journal entry deleted by mistake has no recovery path in the app and is destroyed silently three months later.
  Fix: include journal entries in the bin, with the same restore/purge actions.

- **[RecentlyDeletedViewModel.kt:75-77,99-101]** Issue: permanently deleting a meeting writes no tombstone, and `emptyBin` tombstones only notes and to-dos — journal entries get neither a tombstone nor a Drive delete.
  Risk: the next pull re-inserts the purged item from its surviving Drive file; the "empty bin" appears not to have worked.
  Fix: tombstone and Drive-delete meetings and journal entries on the same paths as notes.

- **[ErrorLog.kt:53,78,83]** Issue: a single shared `SimpleDateFormat` is formatted from `record`, which is documented as callable from any thread, and the `format` call sits outside the `runCatching` in `append`.
  Risk: `SimpleDateFormat` is not thread-safe; concurrent use throws, and here it throws *out of* the logger — inside a crash handler or the `appScope` exception handler, replacing a diagnosable failure with an undiagnosable one.
  Fix: use `DateTimeFormatter`, or a `ThreadLocal`, and move the timestamp inside the guarded block.

- **[TodoDao.kt:updateSortOrder/updateIsPinned, NoteDao.kt: same]** Issue: reordering and pinning update the row without clearing `isSynced`.
  Risk: pin state and manual order never reach Drive, and are silently lost when a device is replaced — while every other edit path does clear the flag.
  Fix: add `isSynced = 0` (and bump `updatedAt`) to both statements.

- **[TodoDao.kt:archiveTodo/archiveAllCompleted]** Issue: archiving clears `isSynced` but leaves `updatedAt` untouched.
  Risk: the push publishes a row whose `updatedAt` predates the change, so a second device's `modifiedAt <= updatedAt` guard can discard it — the archive appears to undo itself.
  Fix: set `updatedAt` alongside `isArchived`, as `setTodoDone` does.

- **[AppDatabase.kt:495-507]** Issue: two separate `addCallback` registrations (`IdFloor`, then `SeedDatabaseCallback`) whose correctness depends on the order they were added.
  Risk: low today — buckets are not a synced table — but reordering or merging them would silently disable the id floor, and its whole point is that it must run before anything is captured.
  Fix: one callback that does both, in an explicit order, with a comment saying why.

- **[IdFloor.kt:37]** Issue: `meetings` is excluded from the id floor on the grounds that meetings are keyed by Drive folder, but `todos.sourceMeetingId`, `notes.sourceMeetingId` and the deep-link extras all address meetings by local row id.
  Risk: on a second device those ids are minted from 1 again, so a pulled to-do's `sourceMeetingId` can resolve to an unrelated meeting.
  Fix: either include `meetings` in the floor, or confirm meeting rows are never pulled cross-device and say so in the comment.

## Module 5 — DriveSyncWorker

- **[DriveSyncWorker.kt:512,760-766]** Issue: `resolveBucketId` creates a missing bucket with `isVault = false` from a name in `todos.json`, and it runs even when `mergeBucketsFromDrive` returned early because `buckets.json` could not be downloaded (network blip, throttling).
  Risk: a vault bucket is recreated locally as an ordinary one and its to-dos become visible in normal views — the exact leak `noteBucketId` was written to close, still open on to-dos.
  Fix: match by name only and skip the to-do this sync when unmatched, as the note path does.

- **[DriveSyncWorker.kt:511]** Issue: `mergeTodosFromDrive` skips any row with `deletedAt != null` outright, so a to-do deleted on the web is never soft-deleted on the phone.
  Risk: the deletion silently fails, and the phone's push at :774 republishes the row with `deletedAt = null` — the web delete undoes itself within fifteen minutes. Notes and journal entries both honour the stamp.
  Fix: soft-delete the local row on a remote `deletedAt`, matching `pullNoteEdits:727`.

- **[DriveSyncWorker.kt:41-55]** Issue: one 60-second budget covers the pull and the whole push, and the push is last.
  Risk: as the library grows the pull eats the budget, the push is cancelled part-way every run, and local notes/journal/chat stop reaching Drive while the worker reports only `retry`.
  Fix: separate timeouts (or run the push first), and log which half timed out.

- **[DriveSyncWorker.kt:892-894,965-967]** Issue: every soft-deleted note and journal entry is re-stamped on Drive on every sync, with no record that the stamp already landed.
  Risk: 90 days of deletions means N Drive writes every fifteen minutes — quota, battery, and it competes for the 60-second budget above.
  Fix: a `deleteStamped` flag (or reuse `isSynced`) so a stamp is written once.

- **[DriveSyncWorker.kt:797]** Issue: the push builds `TodoSyncDto` with a per-row `getSubtasksOnce` query, over `getAllTodosIncludingDeleted()` — soft-deleted rows included.
  Risk: N+1 database round trips on every sync, growing without bound with the bin.
  Fix: one query for all subtasks, grouped by `todoId`.

- **[DriveSyncWorker.kt:812,816,848,860]** Issue: the buckets, templates, preferences and API-key pushes are each wrapped in a bare `runCatching` that discards the failure.
  Risk: `buckets.json` is what tells the web app which buckets are vault; if its upload fails permanently nothing reports it, and the web app falls back to trusting nothing (or worse, stale flags).
  Fix: log each failure to `ErrorLog`, and let a failed `buckets.json` push mark the sync unsuccessful.

- **[DriveSyncWorker.kt:851]** Issue: `preferences.json` is re-uploaded on every 15-minute sync regardless of whether anything changed.
  Risk: constant writes for a file that changes a few times a year; on two devices it also makes the last-writer-wins window permanent rather than occasional.
  Fix: only upload when the serialised snapshot differs from the last published one.

## Modules 5b, 8 — MidnightCleanupWorker, DriveRepository

- **[NoteEditorViewModel.kt:330-346 / MeetingDetailViewModel.kt:193-207 / DriveRepository.kt:756-804]** Issue: `shareNoteToDrive` and `shareMeetingToDrive` grant `{"type":"anyone","role":"reader"}` with no vault check and no confirmation.
  Risk: a note in a vault bucket, or a vault-bucketed meeting's summary, is published to anyone with the link — irreversible in practice, and exactly the rule the web app's share route was hardened to fail closed on.
  Fix: resolve the bucket first and refuse (or require an explicit confirmation, as journal sharing does) when it is a vault bucket.

- **[DriveRepository.kt:815-835,880-892]** Issue: meeting audio is uploaded and downloaded as a whole `ByteArray`, and `uploadMeetingAudio` then concatenates it into a second array to build the multipart body.
  Risk: a 90-minute recording is tens of megabytes held twice — an `OutOfMemoryError` on the upload path, which is the one path where the local file is the only copy in existence.
  Fix: stream the file as a `RequestBody` (or use resumable upload) rather than materialising the bytes.

- **[DriveRepository.kt:1024-1038,1050-1068]** Issue: Drive metadata JSON is built by string interpolation — `{"name":"$name","parents":[…]}` — and `createMeetingFolder` passes a meeting title straight through `createFolderIn`.
  Risk: a title containing a quote or backslash produces malformed JSON and the folder is never created, so the meeting has nowhere to upload to; a crafted title could inject metadata fields.
  Fix: serialise the metadata with kotlinx.serialization instead of interpolating.

- **[DriveRepository.kt:238,979,995-1019]** Issue: values are interpolated into Drive `q` strings with no escaping, unlike the web app's `lib/driveQuery.ts` which exists precisely for this.
  Risk: currently contained (all names are app-generated), but the two clients now differ on a rule that was written down as a rule, so the next user-derived filename reintroduces it.
  Fix: add the same escape helper on this side and route every `q` through it.

- **[DriveRepository.kt:446]** Issue: `parseJournalRaw` strips every `<!-- … -->` from the whole file, not just the header.
  Risk: a journal entry that legitimately contains an HTML comment loses it on the round trip — the same class of bug the chat parser was deliberately written to avoid.
  Fix: parse metadata from the header only and strip comments only there, as `parseChatRaw` does.

- **[MidnightCleanupWorker.kt:41-53]** Issue: expired meetings are purged with no tombstone written, unlike notes, to-dos and journal entries.
  Risk: nothing records that the meeting existed, so a surviving Drive artefact or a second device can reintroduce it.
  Fix: write a meeting tombstone alongside the others at :30.

- **[MidnightCleanupWorker.kt:88-96]** Issue: nothing purges `recently_viewed` or `loose_thread_state` rows whose source has been hard-deleted.
  Risk: `loose_thread_state` grows without bound, keyed `KIND:refId`, and a recycled id could inherit an old "it's dead" dismissal — hiding real work with no way to notice.
  Fix: delete state rows for purged ids in the same pass.

## Module 6a — AmbientBufferService / AmbientBuffer

- **[AmbientBufferService.kt:571-576,581-635]** Issue: the 90-minute auto-cutoff is a `handler.postDelayed` that `stopMeeting()` never cancels — only `shutdown()` clears the handler queue.
  Risk: record, stop, then record again inside 90 minutes and the first recording's cutoff fires and ends the second one early, with nothing to explain it.
  Fix: keep a token for the cutoff (and the `MIC_HANDOVER_MS` post) and remove it in `stopMeeting`.

- **[AmbientBufferService.kt:781]** Issue: `notifyRecordingSaved` uses `id.toInt()` as the notification id.
  Risk: harmless today, but every other id in the app is now clock-seeded (`IdFloor`); the same truncation applied to a note or journal id would silently collide.
  Fix: derive a bounded notification id rather than truncating a `Long`.

## Module 6b — VoiceCaptureService

- **[VoiceCaptureService.kt:246-250]** Issue: when `RECORD_AUDIO` is missing, `onCreate` logs, calls `stopSelf()` and returns *before* `ServiceCompat.startForeground`, and no other path calls it either.
  Risk: the service is started with `startForegroundService` from `CarlsBrainApp` and `BootReceiver`, so this is a `ForegroundServiceDidNotStartInTimeException` — the crash-on-launch this block was written to prevent, moved rather than removed. `AmbientBufferService` follows the opposite rule deliberately.
  Fix: call `startForeground` first, unconditionally, then stand down.

- **[VoiceCaptureService.kt:328-332]** Issue: `ACTION_STOP_LISTENING` is documented as releasing in-progress service speech, but only clears `isListening` and destroys the recogniser — `speaker.release()` is never called.
  Risk: `VoiceCaptureActivity` takes the microphone while the service is still speaking, so Carl hears the tail of one reply over the start of his next utterance; the abandoned `isSpeaking` also suppresses wake-word detections until something else clears it.
  Fix: call `speaker.release()` (and clear `isSpeaking`) on that branch.

- **[VoiceCaptureService.kt:669-671,792]** Issue: `memoryLoadJob?.join()` waits on a Drive fetch with no timeout of its own.
  Risk: bounded only by the shared client's 60-second call timeout, so a slow network means up to a minute of silence mid-conversation with the microphone shut and nothing said — indistinguishable from the app having died.
  Fix: `withTimeoutOrNull` around the join, and carry on with whatever memory is loaded.

- **[VoiceCaptureService.kt:988,1010]** Issue: `[TODO:]` and `[NOTE:]` resolve a bucket name against *all* buckets, vault included, while the prompt lists only non-vault names and `[DONE:]` searches only non-vault to-dos.
  Risk: a voice capture can be filed where voice can never find it again — the exact rule Chat follows ("it must not create what it can never find"), not applied here.
  Fix: match against the non-vault buckets only, falling back to the default.

- **[VoiceCaptureService.kt:1360-1368]** Issue: `playEndTone` releases its `ToneGenerator` on a delayed handler post, which `onDestroy` cancels wholesale with `removeCallbacksAndMessages(null)`.
  Risk: a `ToneGenerator` leaked whenever the service is destroyed within 500 ms of a conversation ending — small, but it holds an audio session.
  Fix: release it in `onDestroy` too, or use a self-contained release.

- **[VoiceCaptureService.kt:1196-1231]** Issue: a failed `TextToSpeech` init leaves `tts` non-null and `ttsReady` false forever, and `initTtsIfNeeded` returns early on the non-null check.
  Risk: the on-device engine never recovers for the life of the process; every fallback utterance becomes an 800 ms silent pause. Callbacks still fire, so nothing breaks visibly — which is why it would never be noticed.
  Fix: null the instance on failure so a later conversation retries.

- **[VoiceCaptureService.kt:1153-1170]** Issue: the KDoc describing `restartWakeWordUnlessRecording` sits immediately above `hasMicPermission`, so it documents the wrong function.
  Risk: none at runtime; it is the explanation of the trickiest handoff in the file, attached to a one-line permission check.
  Fix: move it back onto the function it describes.

## Module 6c — MeetingRecordingService

- **[MeetingRecordingService.kt:onDestroy]** Issue: on the kill path the `Stopped` state — carrying `audioFile.absolutePath` — is published *before* `mediaRecorder.stop()` seals the file.
  Risk: whatever reacts to `Stopped` can pick up an unfinalised m4a and hand it to Fireflies or Whisper. This is the same ordering fault `AmbientBufferService.onDestroy` was fixed for, on the other recording path.
  Fix: stop and release the recorder first, then publish `Stopped`.

- **[MeetingRecordingService.kt:stopRecording]** Issue: no guard against being called twice — the notification's Stop action and the ViewModel can both reach it, and neither checks `isRecording`.
  Risk: a second call re-posts the 2.5-second finaliser and publishes `Stopped` again with a recomputed duration, so the meeting can be processed twice.
  Fix: return early when `isRecording` is already false.

- **[MeetingRecordingService.kt:startRecording]** Issue: no `RECORD_AUDIO` check, unlike `VoiceCaptureService` and `AmbientBufferService`, which both stand down explicitly.
  Risk: with the permission revoked, `MediaRecorder.start()` fails inside `runCatching` and the recogniser errors, so the meeting sits at RECORDING having captured nothing, with no message anywhere.
  Fix: check first and report, as the other two services do.

## Module 7 — receivers, alarms, digest

- **[ReminderActionReceiver.kt:22-34 / ReminderReceiver.kt:35-53]** Issue: both do their work bare inside `try { … } finally { pending.finish() }` with no `runCatching`, on a raw scope with no exception handler. `DigestReceiver`, `SmartNotificationReceiver` and `BusyModeReceiver` all wrap theirs.
  Risk: a throw from `CompleteTodoUseCase.markDone` or a Room read reaches the default handler and kills the process — from a notification action, with no screen open, so the only symptom is the app dying while Carl taps "Mark Done".
  Fix: wrap the body in `runCatching` and record it, matching the other three receivers.

- **[ReminderScheduler.kt:12-42 / ReminderReceiver.kt:66]** Issue: PendingIntent request codes and notification ids are `(todoId and 0x7FFFFFFF).toInt()`, now that `IdFloor` seeds ids from the clock.
  Risk: two devices installed ~24.8 days apart mint id ranges that can collide in the low 31 bits, so cancelling one to-do's reminder cancels another's. Narrow, but the truncation is no longer over small sequential integers.
  Fix: hash the full id, or keep a dedicated small alarm id.

## Modules 9-10 — Calendar, digest, memory writers

- **[MemoryEditorViewModel.kt:29-36,41-52]** Issue: `load()` maps a null from `getMemoryMd()` to `DriveRepository.INITIAL_MEMORY`, but null means "unreachable" as often as "no file"; `save()` then writes whatever is on screen with no version check.
  Risk: open Settings → Memory while offline or on an expired token, tap Save, and months of learned facts are replaced by the seed text. This is the single most destructive path in the app, and CLAUDE.md states the opposite rule — "memory.md is never blind-written".
  Fix: distinguish unreachable from absent (as `restorePreferencesOnFirstSync` does), refuse to save when the load failed, and carry Drive's `modifiedTime` through to the save like the web app's 409 check.

- **[ChatViewModel.kt:216,421]** Issue: chat's memory append writes back the `memoryMd` copy cached when the screen opened, not a fresh read.
  Risk: anything `MemoryLearner`, Health or the Fireflies sync wrote in the meantime is silently erased — exactly the failure `MemoryLearner.appendToMemory` was rewritten to avoid, in a second writer that did not get the fix.
  Fix: route every append through `MemoryLearner`, which already reads fresh under a mutex.

- **[HealthViewModel.kt:148-160 / FirefliesSyncWorker.kt:207-211]** Issue: both read-modify-write `memory.md` fresh but outside `MemoryLearner.writeMutex`.
  Risk: two writers interleaving lose one side's edit. Less severe than the two above because both read fresh, but there are now four writers to one file and only one of them takes the lock.
  Fix: expose the mutex (or a single `MemoryLearner.mutate { }` entry point) and route all four through it.

- **[CalendarRepository.kt:63-88]** Issue: the per-calendar loop `continue`s past any non-2xx response, then unconditionally `deleteAll()` and re-inserts whatever it managed to collect.
  Risk: a partly-failed refresh silently replaces the offline cache with a partial set — if every calendar 401s, the loop completes with zero events and wipes the cache, so the Dashboard shows an empty day with no error. The delete and insert are also not in one transaction.
  Fix: only replace the cache when every calendar fetched successfully, and do it in a transaction.

- **[DigestGenerator.kt:56-62]** Issue: the overall-timeout fallback calls `buildFallbackText(slot, emptyList(), emptyList())`, and the MIDDAY branch renders an empty to-do list as "All clear — no urgent tasks".
  Risk: a timed-out digest tells Carl he has nothing urgent when it never managed to look. For a tool whose value is trust in what it surfaces, a confident wrong answer is worse than no notification.
  Fix: give the timeout path its own text ("Couldn't check just now — tap to open").

## Modules 13-20 — UI, widgets, journal scheduling

- **[DashboardViewModel.kt:435,471,735 / DashboardWidget.kt:90]** Issue: the briefing prompt is built from vault-aware lists (`if (_vaultOpen.value) getActiveTodos() else …`), and the generated text is cached to `UserPreferences.cachedBriefing`, which the home-screen widget renders unconditionally.
  Risk: generate a briefing with the vault open and vault to-do titles are baked into text that then sits on the home screen — outside the biometric gate, outside the vault gate, until the next briefing. Same shape as the recently-viewed strip, but on the lock screen. `DigestGenerator` states the opposite rule for notifications in its own header.
  Fix: only cache a briefing generated with the vault closed, or regenerate the widget's copy from non-vault data.

- **[JournalReminderScheduler.kt:JournalReminderReceiver]** Issue: the reminder notification's title is the template name, with no vault or privacy check.
  Risk: a template whose default bucket is a vault bucket — or that is private by default — announces its name on the lock screen every week. The web app withholds exactly these templates while locked; the phone's own reminder does not.
  Fix: withhold the name (fall back to "Journal") when the template is private-by-default or bucketed into a vault bucket.

- **[CaptureViewModel.kt:321]** Issue: `save()` reads `buckets.value`, a `SharingStarted.WhileSubscribed(5_000)` flow.
  Risk: works only because the screen is collecting it. The identical shape in `ChatViewModel` silently produced an empty list and a fallback that looked applied but changed nothing.
  Fix: `Eagerly`, or read the DAO directly in `save()`.

- **[TodosViewModel.kt:rescheduleOverdueToToday]** Issue: "move overdue to today" preserves the original time of day.
  Risk: an item due 09:00 yesterday, rescued at 14:00, is due 09:00 today — immediately overdue again, so the rescue appears not to have worked.
  Fix: when the shifted time has already passed, move it to a sensible point later today.

## Method note

Modules 13-20 were reviewed as: every ViewModel read in full, plus a targeted sweep of the
Compose screens, widgets, tile, components and `util/` for the priority defect classes (raw
coroutine scopes, `!!`, `runBlocking`, unfiltered vault queries, notification/widget content,
Drive writes with unchecked results). The screens were not read line by line — they are
overwhelmingly layout, and the logic worth reviewing lives in the ViewModels.
