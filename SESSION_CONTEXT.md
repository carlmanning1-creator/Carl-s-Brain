# Carl's Brain — Session Handover & Project Context

_Last updated: 2026-06-18. Use as opening context for a new Claude session._

---

## Active Branch

```
claude/debug-session-error-ZdDFD
```

All work lives here. **Never push to master** without explicit user sign-off.

---

## App Identity

| Field | Value |
|---|---|
| App package | `com.carlmanning.carlsbrain` |
| Current version | `1.8` / versionCode `8` |
| Android stack | Kotlin + Jetpack Compose, AGP 9.0.0, Kotlin 2.1.20, KSP 2.1.20-1.0.31 |
| Room DB version | **17** |
| Web app | Next.js 14 App Router, TypeScript — lives in `/webapp` |

---

## Project Purpose

Carl's Brain is Carl Manning's external memory and ADHD support tool. Claude is the intelligence layer — auto-sorting notes, transcribing voice, summarising, managing todos, maintaining a persistent `memory.md` on Google Drive that is prepended to every Claude call.

Carl is a Deputy at NSW SES, Dubbo Unit. His life buckets: SES, Family, Work, Personal, Kink (vault), Other.

---

## What Was Built (Sessions 1–3)

### v1.8 bug fixes
- `MeetingsScreen.kt` — TRANSCRIBING status now shows spinner (was falling through to `else`); AUDIO_ONLY shows "No transcript" AssistChip with `secondaryContainer` colours
- `MeetingViewModel.init` — `recoverStuckTranscribingMeetings()` added: scans for meetings stuck in TRANSCRIBING on app restart (e.g. app killed mid-Whisper), retries Whisper if audio+key present, otherwise demotes to AUDIO_ONLY
- Version bumped to 1.8 / versionCode 8

### Feature 1 — Recurring todo auto-creation with configurable lead time
**Why:** Carl has ADHD; recurring tasks need to auto-spawn the next instance on completion, with reminders surfacing the right number of days before due.

**Key decisions:**
- `leadDays` (0/1/3/7/14) stored on `TodoEntity` — carries forward to each spawned instance
- Idempotency check via `findActiveRecurringByTitleAndRecurrence()` before inserting — prevents double-spawn if `toggleDone` fires twice
- MONTHLY uses `Calendar.add(Calendar.MONTH, 1)` not `TimeUnit.DAYS.toMillis(30)` to handle variable month lengths
- Lead-time reminder computed as `nextDue - TimeUnit.DAYS.toMillis(leadDays)` when `leadDays > 0`; otherwise offsets previous `reminderAt` by the same interval delta
- "Remind me" UI dropdown only visible when recurrence != None (avoids confusing non-recurring todos)

**Files changed:** `TodoEntity.kt`, `TodoDao.kt`, `TodosViewModel.kt`, `TodoEditorScreen.kt`, `AppDatabase.kt` (MIGRATION_16_17)

### Feature 2 — Meeting action item approval UI
**Why:** Automatic insertion of meeting action items into Todos was too aggressive. Carl needs to review and approve each one.

**Key decisions:**
- Workflow changed: items are NOT auto-added. They surface in the meeting detail as a review list
- Each item has a tick (add to Todos) or dismiss (discard) action
- Count badge on meeting list items (e.g. "2 actions") so Carl can see pending items at a glance without opening each meeting
- Action item regex relaxed to `\[?\s*ACTION:\s*([^|\]\n]+?)\s*\|\s*([^\]\n]+?)\s*\]?` to tolerate slight formatting variation from Claude

**Files changed:** `MeetingViewModel.kt`, `MeetingsScreen.kt`

### Feature 3 — Dashboard home screen widget
**Why:** Carl wanted the app's dashboard content (urgent todos + today's calendar events) accessible without opening the app.

**Key decisions:**
- Glance AppWidget (`GlanceAppWidget` + `GlanceAppWidgetReceiver`) — modern Android widget API
- Shows top 3 urgent/high non-vault todos + today's calendar events
- Vault safety: `getUrgentHighTodosNonVault()` uses INNER JOIN on buckets, filters `isVault=0 AND isArchived=0` — vault items never leak to home screen (which is outside biometric protection)
- 4×3 cell minimum, resizable, 30-min update period
- Tap → opens `MainActivity`; refresh button calls `actionRunCallback<RefreshDashboardAction>()`

**Files created/changed:** `DashboardWidget.kt`, `DashboardWidgetReceiver.kt`, `res/xml/dashboard_widget_info.xml`, `AndroidManifest.xml`

### Feature 4 — Photo/image attachments on notes
**Why:** Carl wanted to attach photos (e.g. SES incident photos, family photos) to notes.

**Key decisions:**
- Attachments stored as Drive file IDs, comma-separated in `NoteEntity.attachments`
- Gallery picker + camera picker both supported
- Thumbnails loaded from Drive and cached in `context.cacheDir/attachments/`
- File attachments (non-image) stored with a `file:displayName:driveId` prefix to distinguish from image IDs
- `persistAttachments()` uses `current.copy(...)` — critical to avoid clobbering other DB fields on partial save
- `save()` similarly uses `existing.copy(...)` to preserve `isPinned` and `sortOrder` (bug caught in review)
- `attachmentUris` column was also added to DB schema in migration 16→17 but is currently **unused** — the live field is `attachments`. Clean this up in a future session (either remove the column or wire it up)

**Files changed:** `NoteEditorViewModel.kt`, `NoteEditorScreen.kt`, `NoteEntity.kt`, `AppDatabase.kt`

### Feature 5 — Manual refresh on web app
**Why:** Web app was fetching data only on mount; Carl wanted a way to force-refresh without reloading the page.

**Key decisions:**
- Simple ↻ button next to section title on Notes, Todos, and Meetings pages
- Spins while loading, disabled during fetch — prevents double-fetch
- No polling; purely manual

**Files changed:** `NotesList.tsx`, `TodosList.tsx`, `MeetingsView.tsx`

### Feature 6 — Vault PIN fallback for biometric failure
**Why:** Android biometric can fail (wet hands, injury, etc.). Without a fallback, Carl is locked out of his vault items.

**Key decisions:**
- PIN hashed with SHA-256 via `MessageDigest` — no extra Android Keystore dependency needed
- Hash stored in DataStore (`KEY_VAULT_PIN_HASH`), never the raw PIN
- `VaultPinDialog` has three modes: SET (two fields: PIN + confirm), CHANGE (old PIN → new PIN), ENTER (one field, compare hash)
- Only shown as fallback when biometric errors AND `vaultPinHash.isNotBlank()` — users without a PIN set don't see it
- "Use PIN" button also available on the biometric retry screen

**Files created/changed:** `UserPreferences.kt`, `VaultPinDialog.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, `MainActivity.kt`

### Web parity fix — Recurrence + leadDays in web TodoEditor
**Why:** Web `TodoSyncDto` had no `recurrence`/`leadDays` fields. Web saves were silently stripping these from Android-created recurring todos.

**Key decisions:**
- Added `recurrence?: "DAILY" | "WEEKLY" | "MONTHLY" | ""` and `leadDays?: number` to `TodoSyncDto`
- TodoEditor now has Repeat dropdown (No repeat/Daily/Weekly/Monthly) and conditional Remind me dropdown (same lead-day options as Android)
- `leadDays` is zeroed if `recurrence` is empty on save — no orphaned lead-days data

**Files changed:** `webapp/src/lib/types.ts`, `webapp/src/components/todos/TodoEditor.tsx`

---

## DB Schema — Room version 17

Combined migration (16 → 17):
```kotlin
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todos ADD COLUMN leadDays INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notes ADD COLUMN attachmentUris TEXT NOT NULL DEFAULT ''")
    }
}
```

**Note:** `attachmentUris` was added speculatively — the live notes attachment field is `attachments` (comma-sep Drive IDs in `NoteEntity`). `attachmentUris` is dead weight. Consider dropping it in migration 17→18 or wiring it up.

---

## Key Files Reference

### Android
| File | Key content |
|---|---|
| `carlsbrain/build.gradle.kts` | versionCode=8, versionName="1.8" |
| `data/local/AppDatabase.kt` | DB version 17, MIGRATION_16_17 |
| `data/local/entity/TodoEntity.kt` | `leadDays: Int = 0` |
| `data/local/entity/NoteEntity.kt` | `attachments: String`, `attachmentUris: String` (unused) |
| `data/local/dao/TodoDao.kt` | `getUrgentHighTodosNonVault()`, `findActiveRecurringByTitleAndRecurrence()` |
| `data/local/dao/BucketDao.kt` | `getBucketByName(name: String)` |
| `data/preferences/UserPreferences.kt` | `vaultPinHash`, `hashPin()` (SHA-256), OpenAI key, Anthropic key |
| `data/remote/WhisperClient.kt` | Posts m4a to OpenAI `/v1/audio/transcriptions`, returns plain text |
| `data/remote/DriveRepository.kt` | `uploadPhoto()`, `uploadFile()`, `downloadPhotoBytes()`, `deletePhoto()` |
| `ui/screens/todos/TodosViewModel.kt` | `spawnNextRecurrence()`, `nextDateMs()`, idempotency check |
| `ui/screens/todos/TodoEditorScreen.kt` | Recurrence + lead-days UI |
| `ui/screens/notes/NoteEditorViewModel.kt` | `save()` uses `existing.copy()`, `addPhoto()`, `addFile()`, `removeAttachment()` |
| `ui/screens/meetings/MeetingViewModel.kt` | Whisper integration, `recoverStuckTranscribingMeetings()`, action item approval |
| `ui/screens/meetings/MeetingsScreen.kt` | TRANSCRIBING spinner, AUDIO_ONLY chip, action count badge |
| `ui/screens/settings/SettingsScreen.kt` | Vault PIN section (Set/Change/Remove) |
| `ui/components/VaultPinDialog.kt` | SET / CHANGE / ENTER modes |
| `widget/DashboardWidget.kt` | Glance widget |
| `widget/DashboardWidgetReceiver.kt` | `GlanceAppWidgetReceiver` |
| `res/xml/dashboard_widget_info.xml` | 4×3, resizable, 30-min update |
| `AndroidManifest.xml` | DashboardWidgetReceiver with `APPWIDGET_UPDATE` intent |
| `MainActivity.kt` | Biometric error → PIN dialog fallback |

### Web (`/webapp/src`)
| File | Key content |
|---|---|
| `lib/types.ts` | `TodoSyncDto` (recurrence, leadDays), `Meeting`, `NoteDto`, `BucketConfig` |
| `components/todos/TodoEditor.tsx` | Repeat + Remind me dropdowns |
| `components/todos/TodosList.tsx` | Refresh button |
| `components/notes/NotesList.tsx` | Refresh button |
| `components/meetings/MeetingsView.tsx` | Refresh button |

---

## Bugs Caught in Review (Already Fixed)

| Bug | Fix |
|---|---|
| Widget query missing `isArchived = 0` | Added to `getUrgentHighTodosNonVault()` WHERE clause |
| `NoteEditorViewModel.save()` creating fresh `NoteEntity` → losing `isPinned`/`sortOrder` | Changed to `existing.copy(...)` pattern |
| Dead condition `&& nextDue != null` in `spawnNextRecurrence` | Removed — `nextDue` was guaranteed non-null by `?: return` above |
| Action item regex too strict — failed on minor Claude output variation | Relaxed to `\[?\s*ACTION:...` |

---

## Security Constraints (Permanent)

- **Wake word** — no key or account of any kind. sherpa-onnx runs fully on-device (Apache-2.0);
  Picovoice was removed after its free tier was terminated on 30 June 2026. The model binaries are
  gitignored and added by hand — see `docs/wake-word.md`.
- **Anthropic API key** — stored in Drive `settings.json`, never committed
- **OpenAI API key** — stored in Drive `settings.json` (web) / UserPreferences DataStore (Android), never committed
- **Vault items** — NEVER appear in notifications (lock screen is outside biometric protection); widget uses vault-safe query
- **`.env.local`** — gitignored; OAuth credentials and `NEXTAUTH_SECRET` must never be committed

---

## Pending / Deferred

| Item | Priority | Notes |
|---|---|---|
| Build + sign v1.8 APK | High | Pull branch locally, run signed release build |
| Merge branch → master | High | After APK tested |
| `attachmentUris` dead column in DB | Low | Remove in migration 17→18 or wire up |
| `ClickableText → Text + LinkAnnotation` migration | Low | `LinkifyText.kt`, `MarkdownText.kt` — deprecation only, not breaking |
| SES Dashboard → Carl's Brain task sync | **Cancelled Aug 2026** | SES role changed; the SES Dashboard app was retired and its module deleted |

---

## Drive Storage Structure

```
/SecondBrain/
  memory.md              ← prepended to every Claude API call
  settings.json          ← Anthropic key, OpenAI key
  /notes/{bucket}/       ← one .md file per note
  todos.json             ← all todos as array of TodoSyncDto
  /audio/                ← unused (audio discarded after Whisper transcription)
  /media/                ← note photo attachments (Drive file IDs referenced in NoteEntity)
```

---

## Recent Commits (branch tip → base)

```
79fdddf docs: add session handover context document
6b1b8e7 feat: add recurrence and leadDays to web TodoSyncDto and TodoEditor
af3a4a0 fix: post-review corrections (widget isArchived, note editor copy, dead condition)
d9a8b16 feat: vault PIN fallback for biometric failure
bf1986d feat: add manual refresh buttons to web app Notes, Todos, and Meetings
f0160f6 feat: meeting action item approval UI enhancements
90fc8a3 feat: recurring todo auto-creation with configurable lead time
9ab2b26 feat: add vault-safe query to TodoDao and use it in DashboardWidget
ef7a344 v1.8: fix TRANSCRIBING/AUDIO_ONLY UI, add stuck-meeting recovery, bump version
```
