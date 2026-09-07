/**
 * Pure helpers for talking to Drive safely.
 *
 * Deliberately dependency-free: lib/drive.ts uses these, and lib/driveGuards.ts uses both, so
 * anything imported here would create a cycle between the three.
 */

/**
 * Escapes a value for interpolation into a Drive `q` string.
 *
 * Drive queries are a string language with single-quoted literals, so an unescaped quote ends
 * the literal and the rest is parsed as query syntax — `x.md' or name = 'memory.md` matches a
 * file the caller never named. Backslash first, or it would re-escape the quotes it just added.
 */
export function escapeDriveQueryValue(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
}

/**
 * Entity ids on both clients are integers — Room autoincrement on the phone, epoch milliseconds
 * on the web — so anything else is not an id this app ever produced.
 *
 * @returns the id as a canonical digit string, or null when it is not one.
 */
export function validEntityId(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const trimmed = String(raw).trim();
  return /^\d{1,19}$/.test(trimmed) ? trimmed : null;
}

/**
 * Whether [bucketName] is one of [vaultBuckets].
 *
 * One helper because there are eight vault gates in this app and they did not agree. Six
 * compared case-insensitively and trimmed; the to-do list and the meetings list used an exact
 * `Array.includes` — and those two are the largest surfaces in the app.
 *
 * The difference is reachable, not theoretical: the Android client matches bucket names with
 * `equals(ignoreCase = true)` everywhere, so nothing guarantees the case in `todos.json`
 * matches the case in `buckets.json`. Where it did not, the vault filter silently passed the
 * item straight through.
 *
 * An empty or missing name is NOT vault — an unfiled item has not been hidden by omission.
 * Callers that need "unknown means withhold" (notes, where a missing bucket comment could be a
 * vault note written before the comment was unconditional) must check that separately; this
 * answers one question only.
 */
export function isVaultBucket(
  bucketName: string | null | undefined,
  vaultBuckets: string[]
): boolean {
  const name = (bucketName ?? "").trim().toLowerCase();
  if (!name) return false;
  return vaultBuckets.some((b) => b.trim().toLowerCase() === name);
}
