# Carl's Brain — Session Context / Handover Doc

_Generated: 2026-06-03. Use this as the opening context for a new Claude session._

---

## Active Branch

```
claude/debug-session-error-ZdDFD
```

All in-progress work lives here. **Never push to master** without explicit sign-off.

---

## App Identity

| Field | Value |
|---|---|
| App package | `com.carlmanning.carlsbrain` |
| Current version | `1.8` / versionCode `8` |
| Android stack | Kotlin + Jetpack Compose, AGP 9.0.0, Kotlin 2.1.20, KSP 2.1.20-1.0.31 |
| Room DB version | **17** |
| Web app | Next.js 14 App Router, TypeScript, `/webapp` |

---

## What Was Built in the Last Two Sessions

### v1.8 fixes (committed)
- `MeetingsScreen.kt` — TRANSCRIBING shows spinner; AUDIO_ONLY shows "No transcript" chip
- `MeetingViewModel.init` — stuck-TRANSCRIBING recovery (Whisper retry or demote to AUDIO_ONLY)
- Version bump to 1.8 / versionCode 8

### Feature set (all committed to branch)

#### 1. Recurring todo auto-creation with configurable lead time
- Files: `TodosViewModel.kt`, `TodoEditorScreen.kt`, `TodoEntity.kt`, `TodoDao.kt`, `AppDatabase.kt`
- `leadDays` field on `TodoEntity` (DB migration 16→17)
- `spawnNextRecurrence()` in `TodosViewModel` — idempotency via `findActiveRecurringByTitleAndRecurrence()`
- DAILY/WEEKLY/MONTHLY/FORTNIGHTLY/CUSTOM intervals via `nextDateMs()`
- Lead-time reminder: `nextDue - TimeUnit.DAYS.toMillis(leadDays)` if `leadDays > 0`
- UI: "Remind me" dropdown only visible when recurrence != None (0/1/3/7/14 days before)

#### 2. Meeting action item approval UI
- Files: `MeetingViewModel.kt`, `MeetingsScreen.kt`
- Action items extracted with regex from Claude response
- Stored as JSON in `pendingActionItems` column
- List screen shows badge: "N actions"
- Detail screen: tick checkbox → moves item to Todos, dismiss removes it

#### 3. Dashboard home screen widget
- Files: `DashboardWidget.kt`, `DashboardWidgetReceiver.kt`, `dashboard_widget_info.xml`, `AndroidManifest.xml`
- Glance AppWidget — top 3 urgent/high non-vault todos + today's calendar events
- Refresh action via `actionRunCallback<RefreshDashboardAction>()`
- Vault-safe: `getUrgentHighTodosNonVault()` INNER JOINs buckets, filters `isVault=0 AND isArchived=0`
- Tap opens `MainActivity`
- Widget info: 4×3 cells minimum, resizable, 30-min update period

#### 4. Photo/image attachments on notes
- Files: `NoteEditorViewModel.kt`, `NoteEditorScreen.kt`, `NoteEntity.kt`, `AppDatabase.kt`
- `attachmentUris` column added (DB migration 16→17, same combined migration as leadDays)
- Drive upload via `DriveRepository.uploadPhoto()` / `uploadFile()`
- Thumbnail strip in NoteEditorScreen; remove attachment with Drive delete
- `persistAttachments()` uses `current.copy(...)` to avoid clobbering other fields
- `save()` uses `existing.copy(...)` pattern to preserve `isPinned` and `sortOrder`

#### 5. Manual refresh on web app
- Files: `NotesList.tsx`, `TodosList.tsx`, `MeetingsView.tsx`
- Refresh ↻ button next to section title, spins while loading, disabled during fetch

#### 6. Vault PIN fallback
- Files: `UserPreferences.kt`, `VaultPinDialog.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, `MainActivity.kt`
- SHA-256 PIN hash stored in DataStore (`KEY_VAULT_PIN_HASH`)
- `VaultPinDialog` — SET / CHANGE / ENTER modes
- SET mode: two fields (PIN + confirm). ENTER mode: one field, compares hash
- `MainActivity.onAuthenticationError`: if `vaultPinHash.isNotBlank()` → shows `VaultPinDialog(ENTER)` instead of just retry
- "Use PIN" button on biometric retry screen
- Settings UI: Set PIN / Change PIN / Remove PIN buttons

#### Web parity fixes
- `webapp/src/lib/types.ts` — `TodoSyncDto` now has `recurrence?` and `leadDays?`
- `webapp/src/components/todos/TodoEditor.tsx` — Repeat dropdown + Remind me dropdown (same options as Android)
- Web saves no longer strip recurrence/leadDays from Android-created todos

---

## DB Schema — Room version 17

**Migration 16 → 17 (combined, in `AppDatabase.kt`)**
```kotlin
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todos ADD COLUMN leadDays INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notes ADD COLUMN attachmentUris TEXT NOT NULL DEFAULT ''")
    }
}
```
Note: `attachmentUris` column exists in schema but Android code uses `attachments` (comma-separated Drive IDs stored in `NoteEntity.attachments`). The `attachmentUris` column is currently unused — either remove it or wire it up in a future session.

---

## Key File Map

### Android
| File | Purpose |
|---|---|
| `carlsbrain/build.gradle.kts` | versionCode=8, versionName="1.8" |
| `data/local/AppDatabase.kt` | Room DB, MIGRATION_16_17, version 17 |
| `data/local/entity/TodoEntity.kt` | `leadDays: Int = 0` |
| `data/local/entity/NoteEntity.kt` | `attachmentUris: String = ""` (unused), `attachments` is the live field |
| `data/local/dao/TodoDao.kt` | `getUrgentHighTodosNonVault()`, `findActiveRecurringByTitleAndRecurrence()` |
| `data/local/dao/BucketDao.kt` | `getBucketByName(name)` |
| `data/preferences/UserPreferences.kt` | `vaultPinHash`, `setVaultPinHash()`, `clearVaultPinHash()`, `hashPin()` (SHA-256) |
| `data/remote/WhisperClient.kt` | Posts m4a to OpenAI Whisper, returns plain transcript |
| `ui/screens/todos/TodosViewModel.kt` | `spawnNextRecurrence()`, `nextDateMs()`, `toggleDone()` |
| `ui/screens/todos/TodoEditorScreen.kt` | Recurrence + lead-days UI |
| `ui/screens/notes/NoteEditorViewModel.kt` | `save()` uses `existing.copy(...)`, `addPhoto()`, `addFile()`, `removeAttachment()` |
| `ui/screens/meetings/MeetingViewModel.kt` | Whisper integration, `recoverStuckTranscribingMeetings()`, action item approval |
| `ui/screens/meetings/MeetingsScreen.kt` | TRANSCRIBING/AUDIO_ONLY cases, action item badge |
| `ui/screens/settings/SettingsScreen.kt` | Vault PIN section |
| `ui/screens/settings/SettingsViewModel.kt` | `saveVaultPin()`, `clearVaultPin()` |
| `ui/components/VaultPinDialog.kt` | SET / CHANGE / ENTER modes |
| `widget/DashboardWidget.kt` | Glance widget, vault-safe todos + calendar events |
| `widget/DashboardWidgetReceiver.kt` | `GlanceAppWidgetReceiver` |
| `res/xml/dashboard_widget_info.xml` | Widget metadata (4×3, resizable) |
| `AndroidManifest.xml` | DashboardWidgetReceiver registered |
| `MainActivity.kt` | Biometric error → VaultPinDialog fallback |

### Web (`/webapp`)
| File | Purpose |
|---|---|
| `src/lib/types.ts` | `TodoSyncDto` with `recurrence?`, `leadDays?`; `Meeting` type |
| `src/components/todos/TodoEditor.tsx` | Repeat + Remind me dropdowns |
| `src/components/todos/TodosList.tsx` | Refresh button |
| `src/components/notes/NotesList.tsx` | Refresh button |
| `src/components/meetings/MeetingsView.tsx` | Refresh button |

---

## Security Constraints (Must Stay Enforced)

- Picovoice access key: entered only via Settings UI, never committed
- Vault bucket items: NEVER in notifications (lock screen is outside biometric gate)
- Anthropic API key: stored in Drive `settings.json`, never committed
- OpenAI API key: stored in Drive `settings.json` (web) / UserPreferences DataStore (Android), never committed
- `.env.local` is gitignored — OAuth credentials and NEXTAUTH_SECRET must never be committed

---

## Pending / Deferred Items

| Item | Status |
|---|---|
| SES Dashboard → Carl's Brain task sync | **On hold** — user said "ses dashboard needs some work still" |
| `ClickableText → Text + LinkAnnotation` migration in `LinkifyText.kt` and `MarkdownText.kt` | Deferred (deprecation, not breaking) |
| `attachmentUris` column in DB schema but unused | LOW — either remove column in migration 17→18 or wire it up |
| Build + sign v1.8 APK | User needs to pull branch and build locally |
| Merge branch to master | Pending user sign-off |

---

## Recent Commit History (branch tip)

```
6b1b8e7 feat: add recurrence and leadDays to web TodoSyncDto and TodoEditor
af3a4a0 fix: post-review corrections across widget, note editor, and recurrence
d9a8b16 feat: vault PIN fallback for biometric failure
bf1986d feat: add manual refresh buttons to web app Notes, Todos, and Meetings
f0160f6 feat: meeting action item approval UI enhancements
90fc8a3 feat: recurring todo auto-creation with configurable lead time
9ab2b26 feat: add vault-safe query to TodoDao and use it in DashboardWidget
ef7a344 v1.8: fix TRANSCRIBING/AUDIO_ONLY UI, add stuck-meeting recovery, bump version
```

---

## Architecture Reminder

```
/SecondBrain/               ← Google Drive root
  memory.md                 ← prepended to every Claude call
  settings.json             ← Anthropic key, OpenAI key
  /notes/{bucket}/
  todos.json
  /audio/
```

- Room DB = source of truth on-device
- WorkManager syncs Room → Drive in background
- Conflict resolution: last-write-wins (single-user)
- Claude calls require internet; voice queues for cleanup when online
- Vault buckets hidden from all normal views; accessible via long-press on brain icon
