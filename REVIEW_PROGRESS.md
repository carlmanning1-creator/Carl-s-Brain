# Codebase review — progress log

Started 2026-09-07. Kotlin/Android app (`carlsbrain/`), ~42,700 lines across ~160 files.

Priority order: 1 fragility · 2 crash likelihood · 3 data loss/corruption · 4 simplification · 5 security.

Resume rule: work down this list; anything marked **done** is not re-reviewed. Findings
accumulate in `REVIEW_FINDINGS.md` as they are found, so an interruption never loses them.

## Modules

| # | Module | Status | Findings |
|---|--------|--------|----------|
| 1 | App shell — `CarlsBrainApp`, `MainActivity`, `AppViewModel`, `BootReceiver`, `navigation/` | **done** | 9 |
| 2 | `data/local` — `AppDatabase`, `IdFloor`, `ErrorLog` | **done** | 3 |
| 3 | `data/local/entity` (13) | **done** (read through the DAOs) | 0 |
| 4 | `data/local/dao` (12) | **done** | 6 |
| 5 | `data/local/worker` — `DriveSyncWorker`, `MidnightCleanupWorker` | **done** | 9 |
| 6 | `data/local/worker` — voice/ambient/meeting services | partial — `AmbientBufferService` done (2); `VoiceCaptureService`, `MeetingRecordingService`, `MeetingUploadWorker`, `FirefliesSyncWorker` **pending** | 2 |
| 7 | `data/local/worker` — digest, alarms, receivers, busy mode | pending | |
| 8 | `data/remote/DriveRepository` | **done** | 7 |
| 9 | `data/remote` — Claude, OpenAI, Fireflies, Calendar, MemoryLearner | partial — `ClaudeClient`, `MemoryLearner` done (4); Fireflies/Calendar/OpenAI/GoogleAuthManager **pending** | 4 |
| 10 | `data/audio`, `data/voice`, `data/health`, `data/export` | partial — `AmbientBuffer`, `PcmAacEncoder`, `BrainExporter` (skim) done, no findings; `WakeWordModel`, `HealthRepository` **pending** | 0 |
| 11 | `data/preferences/UserPreferences` | partial — DataStore delegate confirmed single-instance; full pass **pending** | |
| 12 | `domain/` — chat, journal, loosethread, usecase, model | partial — `Speaker`, `SpeechAudio`, `LooseThreadDetector`, `CompleteTodoUseCase` done (4); `ChatTools`, `JournalTrends`, `JournalReminderScheduler`, `TemplateField`, `JournalTemplateSeeder` **pending** | 4 |
| 13-20 | `ui/` — screens, components, widgets, tile, util | **cross-cutting sweep only** (raw scopes, `!!`, `runBlocking`, unfiltered vault queries, share paths). Per-file pass **pending** | 3 |

## Where to resume

Module 6 (`VoiceCaptureService.kt`, 1435 lines — the largest un-reviewed file and the one that
owns the microphone), then 7, then the remainder of 9-12, then the UI per-file pass starting
with `DashboardViewModel`, `TodoEditorViewModel`, `MeetingViewModel`, `SettingsViewModel`.
