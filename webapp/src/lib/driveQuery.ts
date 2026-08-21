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
