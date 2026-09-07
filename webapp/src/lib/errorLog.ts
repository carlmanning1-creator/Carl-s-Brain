"use client";

/**
 * A client-side error log Carl can copy out of Settings.
 *
 * The counterpart to ErrorLog on the phone, and deliberately narrower. Most of what can go
 * wrong here happens on the server, where the trace goes to Vercel's logs rather than anywhere
 * Carl can reach — but the failures he actually *notices* are the ones that reach the browser:
 * a page that throws, a save that comes back 500, a fetch that never lands. Those are captured
 * here so a bug report is a paste rather than an expedition.
 *
 * Stored in localStorage: it must survive the reload that often follows a crash, and it must
 * not travel — this is a diagnostic for one browser, not a record to sync.
 */

const KEY = "carlsbrain.errorLog";

/** Roughly a dozen entries. Enough that a repeating failure does not evict the first one. */
const MAX_ENTRIES = 40;

export interface LoggedError {
  at: string;
  where: string;
  detail: string;
}

function read(): LoggedError[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as LoggedError[]) : [];
  } catch {
    // Corrupt or unavailable storage must not itself break the page.
    return [];
  }
}

export function logError(where: string, detail: unknown): void {
  if (typeof window === "undefined") return;
  try {
    const text =
      detail instanceof Error
        ? `${detail.name}: ${detail.message}\n${detail.stack ?? ""}`
        : String(detail);
    const entries = read();
    entries.push({
      at: new Date().toISOString(),
      where,
      // Truncated: a bundled stack trace can run to thousands of characters, and the top of it
      // is the part that identifies the fault.
      detail: text.slice(0, 4000),
    });
    window.localStorage.setItem(
      KEY,
      JSON.stringify(entries.slice(-MAX_ENTRIES))
    );
  } catch {
    // Storage full or blocked. A logger that throws turns a diagnosable failure into an
    // undiagnosable one, so this gives up quietly.
  }
}

/** The whole log as pasteable text, oldest first. */
export function readErrorLog(): string {
  const entries = read();
  if (entries.length === 0) return "No errors recorded.";
  return entries
    .map((e) => `${e.at}  ${e.where}\n${e.detail}`)
    .join("\n\n");
}

export function clearErrorLog(): void {
  try {
    window.localStorage.removeItem(KEY);
  } catch {
    // Nothing useful to do.
  }
}

/**
 * Captures what the browser reports on its own — uncaught errors and rejected promises.
 *
 * Idempotent, because React strict mode mounts effects twice in development and a second set
 * of listeners would double every entry.
 */
let installed = false;

export function installErrorCapture(): void {
  if (installed || typeof window === "undefined") return;
  installed = true;

  window.addEventListener("error", (event) => {
    logError(
      `window.error ${event.filename ?? ""}:${event.lineno ?? 0}`,
      event.error ?? event.message
    );
  });

  window.addEventListener("unhandledrejection", (event) => {
    logError("unhandled promise rejection", event.reason);
  });
}
