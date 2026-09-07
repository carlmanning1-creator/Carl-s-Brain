# Full review, pass 2 — progress log

Started 2026-09-07, at commit `71fb14c`. Covers **both** clients:
`carlsbrain/` (~44,900 lines Kotlin, 161 files) and `webapp/` (~10,400 lines TS, 66 files),
plus the manifest, Gradle files and resources, which no pass has read.

Priority order: 1 fragility · 2 crash likelihood · 3 data loss/corruption · 4 simplification · 5 security.

## Rules for this pass

- **Read, do not sweep.** Pass 1 marked the Compose screens "done" on the strength of a grep
  sweep. They are the largest un-read body of code in the repo and are re-listed here in full.
- **Use the earlier files as reference, not as a substitute.** `REVIEW_FINDINGS.md` and
  `REVIEW_WEBAPP.md` record what was already found; anything marked fixed there is not
  re-reported unless the fix is wrong or introduced something new. Files changed by those fixes
  ARE re-read — new code has not been reviewed by anyone.
- **Resume rule.** A later session reads this file first, skips every module marked **done**,
  and continues at the first unmarked one. Never restart.
- Findings accumulate in `REVIEW2_FINDINGS.md` as they are found.

## Modules

| # | Module | Lines | Status | Findings |
|---|--------|-------|--------|----------|
| A1 | Build + manifest: `build.gradle.kts` ×2, `settings.gradle.kts`, `gradle.properties`, `AndroidManifest.xml`, `proguard` | ~400 | **done** | 8 |
| A2 | App shell: `CarlsBrainApp`, `MainActivity`, `AppViewModel`, `BootReceiver`, `navigation/` | ~1,500 | **done** | 7 |
| A3 | `data/local`: `AppDatabase`, `IdFloor`, `ErrorLog`, entities, DAOs | ~2,600 | **done** | 7 |
| A4 | `data/local/worker`: sync + cleanup | ~1,300 | **done** | 7 |
| A5 | `data/local/worker`: voice / ambient / meeting services | ~2,700 | **done** | 7 |
| A6 | `data/local/worker`: digest, alarms, receivers, busy mode, media button | ~1,300 | **done** | 6 |
| A7 | `data/remote`: Drive, Claude, OpenAI, Fireflies, Calendar, GoogleAuth, MemoryLearner | ~2,600 | **done** | 10 |
| A8 | `data/`: audio, voice, health, export, preferences | ~1,900 | **done** | 6 |
| A9 | `domain/`: chat, journal, loosethread, usecase, model | ~1,800 | **done** | 5 |
| A10 | `ui/screens/dashboard` — **screens not previously read** | ~2,900 | **done** | 4 |
| A11 | `ui/screens/todos` + `notes` — **not previously read** | ~3,300 | **done** | 8 |
| A12 | `ui/screens/meetings` + `capture` — **not previously read** | ~2,900 | **done** | 7 |
| A13 | `ui/screens/journal` + `chat` — **not previously read** | ~2,200 | **done** | 7 |
| A14 | `ui/screens/settings` + `health` + `calendar` + `search` + `onboarding` — **not previously read** | ~5,300 | **done** | 6 |
| A15 | `ui/components`, `ui/theme`, `ui/tile`, `widget/`, `util/`, `ui/VoiceCaptureActivity` | ~2,300 | **done** | 2 |
| W1 | `webapp` lib: auth, middleware, vault, driveQuery, driveGuards, fileFormat, types | ~900 | **done** | (with W2) |
| W2 | `webapp` lib: drive, claude, chatTools, calendar, recurrence, errorLog, memoryPrompt | ~1,700 | **done** | 7 |
| W3 | `webapp` API routes (18) | ~2,000 | **done** | 10 |
| W4 | `webapp` components + hooks + pages — **not previously read** | ~5,400 | pending | |
| W5 | `webapp` config: `next.config.ts`, `vercel.json`, `tsconfig.json`, `package.json`, `tailwind.config.ts` | ~200 | pending | |

## Carried forward, still open

From `REVIEW_FINDINGS.md` (Android, Low tier — never fixed) and `REVIEW_WEBAPP.md`
(Medium + Low — never fixed). Those files remain the record; this pass re-verifies them in
place rather than re-deriving them, and folds any still-valid ones into the final report.
