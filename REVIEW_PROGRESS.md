# Codebase review — progress log

Started 2026-09-07, completed 2026-09-07. Kotlin/Android app (`carlsbrain/`), ~42,700 lines.

Priority order: 1 fragility · 2 crash likelihood · 3 data loss/corruption · 4 simplification · 5 security.

Findings are in `REVIEW_FINDINGS.md`. The Critical/High/Medium findings from the first pass were
fixed in commit `677b745`; the second pass (modules 6-20) is reported but not yet fixed.

## Modules

| # | Module | Status | Findings |
|---|--------|--------|----------|
| 1 | App shell — `CarlsBrainApp`, `MainActivity`, `AppViewModel`, `BootReceiver`, `navigation/` | **done** | 9 |
| 2 | `data/local` — `AppDatabase`, `IdFloor`, `ErrorLog` | **done** | 3 |
| 3 | `data/local/entity` | **done** (read through the DAOs) | 0 |
| 4 | `data/local/dao` | **done** | 6 |
| 5 | `data/local/worker` — `DriveSyncWorker`, `MidnightCleanupWorker` | **done** | 9 |
| 6 | `data/local/worker` — voice/ambient/meeting services | **done** | 12 |
| 7 | `data/local/worker` — digest, alarms, receivers, busy mode | **done** | 3 |
| 8 | `data/remote/DriveRepository` | **done** | 7 |
| 9 | `data/remote` — Claude, OpenAI, Fireflies, Calendar, MemoryLearner | **done** | 6 |
| 10 | `data/audio`, `data/voice`, `data/health`, `data/export` | **done** | 0 |
| 11 | `data/preferences/UserPreferences` | **done** | 0 |
| 12 | `domain/` — chat, journal, loosethread, usecase, model | **done** | 6 |
| 13-20 | `ui/` — screens, components, widgets, tile, util | **done** (ViewModels in full; screens by targeted sweep — see the method note in REVIEW_FINDINGS.md) | 5 |

## Themes worth remembering

1. **The vault rule holds wherever it was written down and is absent at every edge nobody
   revisited.** Recently-viewed, Recently Deleted, the two Share buttons (all fixed), and now
   the cached briefing on the home-screen widget and the journal reminder's notification title.
   Any surface that keeps its *own copy* of text derived from vault data is a leak that outlives
   the vault being locked.

2. **`memory.md` has four writers and only one follows the documented rule.** `MemoryLearner`
   reads fresh under a mutex; `MemoryEditorViewModel`, `ChatViewModel`, `HealthViewModel` and
   `FirefliesSyncWorker` do not. The memory editor can write the seed over everything.

3. **A null from a network read is not "absent".** The same conflation appears in
   `MemoryEditorViewModel`, `ChatViewModel`'s seeding, and `CalendarRepository`'s cache refresh.
   `restorePreferencesOnFirstSync` gets it right and is the model to copy.
