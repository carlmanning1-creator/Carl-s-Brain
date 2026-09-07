# Web app review — findings

2026-09-07. Next.js app (`webapp/`), ~10,400 lines across 66 TS/TSX files.

Priority order: 1 fragility · 2 crash likelihood · 3 data loss/corruption · 4 simplification · 5 security.

## Coverage

Read in full: `lib/` (all of it), every `app/api/**/route.ts`, `middleware.ts`, `hooks/`.
Targeted sweep of `components/` and `app/*/page.tsx` for the priority defect classes
(client-side vault filtering, unchecked `fetch`, `dangerouslySetInnerHTML`, id handling,
fields dropped on save) with the parts that showed hits read in full.

Two things checked exhaustively and found sound: **every** API route verifies
`getServerSession` before doing anything (`/api/health` is deliberately public and returns
only `{status:"ok"}`), and no route filters vault content in the browser — it is all
server-side, as intended.

## Fixed — commit follows this file

Every Critical and High item below was fixed. The Medium and Low items stand.

## Critical

- **[lib/fileFormat.ts:22-68 · app/api/drive/journal/route.ts:74-90 · lib/drive.ts:80-104]**
  Issue: `JournalEntryDto` has no `answersJson` or `mood`, so `parseJournalFile` never reads
  `<!-- answers: … -->` or `<!-- mood: … -->` and `serialiseJournalFile` never re-emits them —
  while it does write a fresh `updatedAt`.
  Risk: edit a templated journal entry on the laptop and its structured answers are stripped
  from Drive. The phone then accepts the edit (`file.updatedAt > local.updatedAt`) and assigns
  `answersJson = file.answersJson`, i.e. blank — so every Training/Kink score on that entry is
  destroyed on the next sync and disappears from the Trends charts. Silent and irreversible.
  It breaks the rule stated at the top of that same file and named in CLAUDE.md's wire format.
  Fix: carry `answersJson` and `mood` through the DTO, parser, serialiser and POST route,
  exactly as `attachments` and `bucket` already are.

## High

- **[app/api/drive/meetings/route.ts:311-336 (PATCH)]** Issue: `folderId` is taken from the
  request body and used to write `transcript.md`, `summary.md`, `actions.json` and `meta.json`
  with no `isUnderSecondBrain` check, no `meetingFolderIsVisible` check and no id validation.
  Risk: the OAuth token has full `drive` scope, so this writes into *any* folder in Carl's
  Drive, and it can overwrite a vault-bucketed meeting's transcript while the vault is locked.
  `lib/driveGuards.ts` exists for exactly this and its header says so; the audio and share
  routes were hardened and this one — the only *write* path taking a folder id — was missed.
  Fix: `meetingFolderIsVisible` before any write, failing closed.

- **[app/api/meetings/audio/route.ts:22-58]** Issue: `meetingId` is a caller-supplied Drive
  folder id, used unescaped in a `q` string and directly as `parents`, with no guard.
  Risk: audio written into any folder in Carl's Drive, and `recording.webm` overwritten inside
  a vault meeting folder. The unescaped interpolation is the precise thing
  `escapeDriveQueryValue` was written to prevent.
  Fix: validate, escape, and apply `meetingFolderIsVisible`.

- **[app/api/drive/todos/route.ts:32 · app/api/drive/meetings/route.ts:231]** Issue: these two
  match vault buckets with `vaultBuckets.includes(x)` — exact and case-sensitive. The other six
  sites (journal, notes, journal-templates, chat, and both in `driveGuards`) use a
  case-insensitive, trimmed comparison.
  Risk: the phone matches bucket names case-insensitively throughout, so `todos.json` need not
  agree with `buckets.json` on case. Where it does not, the vault filter silently fails — on
  the to-do list and the meetings list, two of the largest surfaces.
  Fix: one shared `isVaultBucket(name, vaultBuckets)` helper used by all eight sites.

- **[lib/driveGuards.ts:60-84]** Issue: `meetingFolderBucket` returns `""` from its `catch`,
  and `meetingFolderIsVisible` reads `""` as "unfiled, therefore visible".
  Risk: an unreadable or rate-limited `meta.json` makes a vault-bucketed meeting visible and
  its audio streamable. `fileIsShareable` in the same file refuses a note whose bucket it
  cannot determine — so the two halves disagree about whether unknown means safe.
  Fix: distinguish "no meta.json" (genuinely unfiled) from "could not read it" (refuse).

- **[lib/drive.ts:150-168]** Issue: `getJournalEntries` fetches every entry's content in one
  unbounded `Promise.all`, and a failed fetch returns `null` and is filtered out.
  Risk: a few hundred entries is a few hundred simultaneous Drive requests; Drive answers some
  with 403 `userRateLimitExceeded` and those entries silently disappear. Not "the journal failed
  to load" — just some entries missing, which reads exactly like data loss.
  Fix: bound the concurrency, and report unreadable entries rather than dropping them.

## Medium

- **[components/notes/NoteEditor.tsx:35 · app/api/drive/notes/route.ts:80]** Issue: the editor
  initialises the bucket picker to `note?.bucket ?? "Personal"`, and the route falls back to
  `bucket ?? "Personal"`.
  Risk: a note whose file carries no bucket comment is visible with the vault open; opening and
  saving it silently relabels it into a public bucket. `serialiseNoteFile`'s own comment says
  an unknown bucket must stay unknown "instead of being silently relabelled into a public
  bucket by an edit" — the serialiser does that correctly and both callers defeat it.
  Fix: keep the unknown state; show it as "Unfiled" in the picker.

- **[components/notes/NoteEditor.tsx:62-76 · lib/driveGuards.ts:104]** Issue: `fileIsShareable`
  returns true for any note or meeting when the vault is open, and the client shows no
  confirmation.
  Risk: with the vault open, one click publishes a vault note to anyone with the link, which
  cannot be recalled. The phone now warns first (Carl's decision: warn and continue), so the
  two clients differ on the same irreversible action.
  Fix: mirror the phone's confirmation.

- **[app/api/drive/todos/route.ts:113-141]** Issue: `getAllTodosRaw` interpolates `folderId`
  into a `q` string without `esc()`, unlike every other query in the codebase, and performs
  three separate `await import("googleapis")` calls plus one unused binding.
  Risk: contained today (the id comes from Drive), but it is the one place that breaks the rule
  `lib/driveQuery.ts` exists to enforce.
  Fix: use `esc()`, and hoist the import.

- **[app/api/drive/journal/route.ts:105-110]** Issue: the DELETE validates its id with
  `Number(...)` rather than `validEntityId`, unlike the notes and chat routes.
  Risk: safe in practice — `Number()` of anything non-numeric is `NaN` and is rejected — but the
  guarantee comes from coincidence rather than from the shared gate, and it is the gate that is
  documented as the rule.
  Fix: use `validEntityId` here too.

## Low

- **[middleware.ts:5]** The wrapped middleware takes `req` and never uses it.
- **[app/api/meetings/audio/route.ts:68-70]** Two imports kept alive only by `void` statements.
- **[lib/drive.ts:37-47]** `folderIdPromise` caches the SecondBrain folder id for the lifetime
  of the server instance with no invalidation. Fine for one user and immutable ids; it would
  hold a stale id if the folder were ever recreated.
- **[lib/chatTools.ts:245-249]** A failed tool returns `err.message` to the model, which could
  carry a Drive file name.

## What is genuinely good here

Worth recording so it does not get "tidied" later:

- Vault filtering is server-side everywhere, with `hiddenCount` so the UI can say how many
  without naming any.
- `lib/driveQuery.ts` / `lib/driveGuards.ts` are the right abstraction, and the notes, chat,
  share and audio routes use them properly. The gaps above are the routes that were missed,
  not a flawed design.
- The todos POST spread-merges onto the stored row so fields the web does not understand
  survive — the correct answer to a shared wire format, and the opposite of the journal bug.
- `lib/auth.ts` refreshes the Google token properly and flags a failed refresh so the
  middleware signs the user out rather than leaving a working-looking app where everything 401s.
- `lib/vault.tsx` documents plainly that it is a visibility toggle and not a security control,
  and why — rather than implying a protection it does not provide.
