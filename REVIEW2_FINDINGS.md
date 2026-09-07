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
