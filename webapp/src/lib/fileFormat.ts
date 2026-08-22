import type { NoteDto } from "./types";
import type { JournalEntryDto } from "./drive";

/**
 * The on-Drive file formats, as pure functions.
 *
 * Separated from lib/drive.ts so they can be tested without Google credentials or a network —
 * and they badly needed testing. Every one of the format bugs found in the August 2026 review
 * was a field silently dropped on a round trip: the journal bucket, note attachments, the
 * bucket comment on an untitled note. Each was invisible until a device restore, and each is
 * one assertion here.
 *
 * The rule these encode: **anything that rewrites a file must re-emit every comment it did not
 * author.** Keep in step with DriveRepository on the Android side.
 */

/**
 * Journal entries are stored one file per entry as `journal_<id>.md`, with metadata in HTML
 * comments so the file stays readable markdown. Written by the Android client; this parses the
 * same shape. Keep in step with DriveRepository.uploadJournalEntry.
 */
export function parseJournalFile(id: number, raw: string): JournalEntryDto {
  const privateMatch = raw.match(/<!--\s*private:\s*(true|false)\s*-->/i);
  const createdMatch = raw.match(/<!--\s*createdAt:\s*(\d+)\s*-->/i);
  const promptMatch = raw.match(/<!--\s*prompt:\s*([\s\S]*?)-->/i);
  const attachmentsMatch = raw.match(/<!--\s*attachments:\s*([^\n]*?)-->/i);
  const bucketMatch = raw.match(/<!--\s*bucket:\s*([^\n]*?)-->/i);
  const deletedMatch = raw.match(/<!--\s*deletedAt:\s*(\d+)\s*-->/i);
  const content = raw
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/^\s+/, "");
  return {
    id,
    content,
    prompt: promptMatch ? promptMatch[1].trim() : "",
    isPrivate: privateMatch ? privateMatch[1].toLowerCase() === "true" : false,
    // Falling back to now would sort a malformed entry to the top of the list every time it
    // loaded, so 0 is used instead — it sorts last and is visibly wrong rather than plausible.
    createdAt: createdMatch ? parseInt(createdMatch[1], 10) : 0,
    attachments: attachmentsMatch ? attachmentsMatch[1].trim() : "",
    bucket: bucketMatch ? bucketMatch[1].trim() : "",
    deletedAt: deletedMatch ? parseInt(deletedMatch[1], 10) : null,
  };
}

export function serialiseJournalFile(entry: JournalEntryDto, now = Date.now()): string {
  const lines = [
    `<!-- private: ${entry.isPrivate} -->`,
    `<!-- createdAt: ${entry.createdAt} -->`,
  ];
  // The prompt is written into an HTML comment, so an unescaped "-->" inside it would end the
  // comment early and spill the rest into the entry body.
  if (entry.prompt) {
    lines.push(`<!-- prompt: ${entry.prompt.replace(/-->/g, "--&gt;")} -->`);
  }
  if (entry.attachments) {
    lines.push(`<!-- attachments: ${entry.attachments} -->`);
  }
  // Re-emitted so a laptop edit does not strip the entry's bucket from Drive. The phone keeps
  // its local bucket when this is absent, but a new device would restore the entry unfiled —
  // and an entry that was in a vault bucket would come back visible.
  if (entry.bucket) {
    lines.push(`<!-- bucket: ${entry.bucket} -->`);
  }
  // Same purpose as on notes: without it the phone cannot tell this copy is newer.
  lines.push(`<!-- updatedAt: ${now} -->`);
  lines.push("", entry.content);
  return lines.join("\n");
}

export function parseNoteFile(id: string, content: string): NoteDto {
  const lines = content.split("\n");
  let title = id;
  let bodyStart = 0;

  if (lines[0].startsWith("# ")) {
    title = lines[0].slice(2).trim();
    bodyStart = 1;
  }
  // Skip every leading metadata comment, not just one. There are now two (bucket and
  // updatedAt), and the old single-line skip would have left the second glued to the body.
  while (
    lines[bodyStart]?.trim().startsWith("<!--") ||
    lines[bodyStart]?.trim() === ""
  ) {
    if (lines[bodyStart].trim() === "" && !lines[bodyStart + 1]?.trim().startsWith("<!--")) {
      bodyStart++;
      break;
    }
    bodyStart++;
  }

  // The bucket comment, or "" when the file does not carry one.
  //
  // Deliberately NOT defaulted to a real bucket. Untitled notes used to be written with no
  // bucket comment at all, and defaulting them to "Personal" meant an untitled note in a vault
  // bucket rendered while the vault was locked — and was permanently relabelled if edited. An
  // unknown bucket is now treated as unknown, and the notes route hides it while locked.
  let bucket = "";
  const metaMatch = content.match(/<!--\s*bucket:\s*(.+?)\s*-->/);
  if (metaMatch) bucket = metaMatch[1].trim();

  const attachments =
    content.match(/<!--\s*attachments:\s*([^\n]*?)-->/)?.[1]?.trim() ?? "";
  const deletedAt = content.match(/<!--\s*deletedAt:\s*(\d+)\s*-->/)?.[1];

  return {
    id,
    title,
    content: lines.slice(bodyStart).join("\n").trim(),
    bucket,
    attachments,
    deletedAt: deletedAt ? parseInt(deletedAt, 10) : null,
  };
}

/**
 * Builds a note file. The counterpart to [parseNoteFile]; the two must round-trip.
 *
 * A blank bucket is written as no comment at all, rather than as an empty or invented one. The
 * note stays "unknown bucket" — which the notes route withholds while the vault is locked —
 * instead of being silently relabelled into a public bucket by an edit.
 */
export function serialiseNoteFile(
  title: string,
  content: string,
  bucket: string,
  attachments = "",
  now = Date.now()
): string {
  const bucketLine = bucket.trim() ? `<!-- bucket: ${bucket.trim()} -->\n` : "";
  // Echoed back rather than dropped. The web app has no attachment UI, but a note edited here
  // must not lose the photos added on the phone — the files would stay in Drive with nothing
  // referencing them.
  const attachmentLine = attachments.trim()
    ? `<!-- attachments: ${attachments.trim()} -->\n`
    : "";
  return `# ${title}\n${bucketLine}${attachmentLine}<!-- updatedAt: ${now} -->\n\n${content}`;
}

/**
 * Stamps a file as deleted, preserving everything else in it.
 *
 * Re-stamped rather than appended to, so deleting twice cannot stack markers. The file stays on
 * Drive deliberately: the phone reads the stamp, soft-deletes, and keeps the item recoverable
 * for 90 days. Trashing it instead made the phone treat it as a lost upload and re-upload.
 */
export function stampDeleted(raw: string, now = Date.now()): string {
  const withoutMarkers = raw
    .replace(/<!--\s*deletedAt:[^\n]*?-->\n?/g, "")
    .replace(/<!--\s*updatedAt:[^\n]*?-->\n?/g, "");
  return `<!-- deletedAt: ${now} -->\n<!-- updatedAt: ${now} -->\n${withoutMarkers}`;
}
