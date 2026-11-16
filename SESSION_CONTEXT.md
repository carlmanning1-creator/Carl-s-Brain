# Carl's Brain — Session Handover & Project Context

_Last updated: 2026-11-16, version 2.28. Opening context for a new Claude session._

> Read `CLAUDE.md` first. It holds the standing instructions (approval before building,
> multiple-choice questions, the mandatory pre-commit quality gate, fast-forwarding `master`)
> and the architecture decision record — the *why* behind nearly every rule below. This file
> is current state only: where the code is, how it is built, and what is still open.

---

## Branches

Work happens on `claude/jolly-mendel-c2w71n`. After every push, `master` is fast-forwarded to
the same commit (standing permission, fast-forward only — see `CLAUDE.md`). Vercel builds the
web app from `master`; Carl builds the phone from the working branch in Android Studio.

---

## App identity

| Field | Value |
|---|---|
| App package | `com.carlmanning.carlsbrain` |
| Current version | `2.28` / versionCode `45` |
| Room DB version | **30** (`exportSchema = true`) |
| Toolchain | AGP 9.0.0, Kotlin 2.1.20, KSP 2.1.20-1.0.31 |
| SDK | compileSdk 36, targetSdk 36, minSdk 28 |
| Compose BOM | 2025.05.01; Glance 1.1.1 |
| Modules | `:carlsbrain` only |
| Web app | Next.js App Router + TypeScript in `/webapp`, deployed on Vercel from `master` |

---

## Build state — read this first

**Nothing is compiled in the authoring environment** — AGP 9.0.0 does not resolve in the
sandbox. Carl builds locally and pastes errors back verbatim. Verify every API name and
dependency coordinate against real sources; never tell him something compiles.

### Room schemas

- Committed: 22, 23, 25, 26, 28, 29.
- **24 and 27 are a permanent gap.** They were never committed and exist nowhere in git
  history (checked November 2026). `MigrationTest` cannot validate the 23→24, 24→25, 26→27 and
  27→28 steps from exported JSON. This is recorded, not outstanding — do not keep listing it.
- **30 is not lost:** the next Android Studio build writes `carlsbrain/schemas/…/30.json`.
  Committing that file closes it.

---

## What exists (November 2026)

Screens: Dashboard, Quick Capture, Todos, Notes, Chat (+ thread list), Calendar, Journal
(templates, trends), Meetings, Health, Search, Settings (memory editor, Recently Deleted,
Diagnostics), Onboarding, plus the write-only **QuickAddActivity** and the voice overlay.

Background pieces worth knowing by name — each has its own section in `CLAUDE.md`:

- **`MicCoordinator`** (`data/voice/`): no passive listener (wake word, ambient buffer) holds
  the microphone while any app screen is open; re-arms half a second after the app leaves.
- **`CommandCapture`** + Silero VAD (`data/voice/`): records a spoken command and ends it on
  silence. Used by "Hey Brain" (inside the wake-word loop's own microphone) and, through
  `TapToTalkRecorder`, by the overlay, Quick Capture and both editors. Transcribed by OpenAI
  (`gpt-4o-transcribe`, falling back to `whisper-1`); Google's recogniser only when offline,
  with no OpenAI key, or with no VAD model.
- **Today surfaces** (`domain/today/`, `data/today/`, `widget/TodayWidget.kt`): one ranker
  (`NextThingRanker`) behind the home-screen widget, the actionable check-ins and the pinned
  Today notification. `TodayClock` redraws them when time alone changes the answer.
- **`TodoMarker`** (`domain/capture/`): the one parser for `[TODO: title | bucket | priority |
  due | remind]` and the overlay's JSON.
- **Meetings**: record → Whisper → Claude analysis, else parked as AUDIO_ONLY. **Fireflies was
  removed in 2.28**; installed copies cancel its job, move stuck meetings on, delete the key and
  strip its block from memory.md, each once.

---

## Open items

- **On-device testing outstanding:** "Hey Brain" in the car over Bluetooth; the 2-minute
  meeting recording check for silent gaps (live transcript sharing the microphone); everything
  in the 2.25–2.28 checklists.
- **Speaker labels** (what Fireflies offered): OpenAI's `gpt-4o-transcribe-diarize` does it at
  Whisper's price but is deprecated, shutting down 26 February 2027, with no named successor.
  Not built — revisit when OpenAI names a replacement.
- Parked features, scoped but unbuilt: see "Next / future features" in `CLAUDE.md`.

---

## Working agreement with Carl

- He builds and installs the APK himself, and pastes build errors verbatim.
- Substantial builds are scoped and approved first; he prefers multiple-choice questions.
- State uncertainty rather than a confident guess — he has been burned by the latter.
- Commit messages explain *why*, not just what.
- The quality gate in `CLAUDE.md` is mandatory before every commit.
