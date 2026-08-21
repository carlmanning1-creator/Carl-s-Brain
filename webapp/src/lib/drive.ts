import { google, type drive_v3 } from "googleapis";
import { escapeDriveQueryValue as esc } from "./driveQuery";
import type { TodoSyncDto, NoteDto } from "./types";

// ─── Auth helper ───────────────────────────────────────────────────────────────

function getDriveClient(accessToken: string) {
  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  return google.drive({ version: "v3", auth });
}

// ─── Folder helpers ────────────────────────────────────────────────────────────

/**
 * In-flight lookup, so concurrent requests in one server instance share a single
 * check-and-create instead of racing each other into two folders.
 *
 * This is a mitigation, not a guarantee: separate serverless instances do not share it.
 * The real protection is that this function only ever creates when the list call
 * SUCCEEDS and returns nothing — a failed list throws rather than being mistaken for
 * "no folder exists". That mistake, in the Android client, is what fragmented Carl's
 * Drive into seventeen SecondBrain folders and repeatedly orphaned his memory.
 */
let folderIdPromise: Promise<string> | null = null;

export async function getSecondBrainFolderId(
  accessToken: string
): Promise<string> {
  if (folderIdPromise) return folderIdPromise;
  folderIdPromise = resolveSecondBrainFolderId(accessToken).catch((err) => {
    // Never cache a failure — the next request must be free to try again.
    folderIdPromise = null;
    throw err;
  });
  return folderIdPromise;
}

async function resolveSecondBrainFolderId(accessToken: string): Promise<string> {
  const drive = getDriveClient(accessToken);

  // orderBy createdTime so that if duplicates ever exist again, every caller —
  // web and Android alike — agrees on the same one rather than picking arbitrarily.
  // The Android client applies the same ordering.
  const res = await drive.files.list({
    q: "name = 'SecondBrain' and mimeType = 'application/vnd.google-apps.folder' and trashed = false and 'root' in parents",
    fields: "files(id, name)",
    orderBy: "createdTime",
    spaces: "drive",
  });

  if (res.data.files && res.data.files.length > 0) {
    return res.data.files[0].id!;
  }

  // Create only when the search definitively succeeded and found nothing. If the call
  // above fails it throws, and this line is never reached — which is the point.
  const created = await drive.files.create({
    requestBody: {
      name: "SecondBrain",
      mimeType: "application/vnd.google-apps.folder",
      parents: ["root"],
    },
    fields: "id",
  });

  return created.data.id!;
}

// ─── Journal ───────────────────────────────────────────────────────────────────

export interface JournalEntryDto {
  id: number;
  content: string;
  prompt: string;
  isPrivate: boolean;
  createdAt: number;
  /**
   * Comma-separated Drive file ids, in the same encoding the Android client uses: a photo is a
   * bare id, anything else is `file:<name>:<id>`. Carried through verbatim rather than
   * interpreted — the web app does not render attachments yet, but re-serialising without this
   * would silently strip them from an entry simply because it was edited on the laptop.
   */
  attachments?: string;
  /**
   * The life bucket the entry is filed under, by name, or "" when the entry is unfiled or the
   * writer knows nothing about journal buckets. Names rather than ids, because ids are
   * per-device. A vault bucket hides the entry exactly as it hides a note, so this has to be
   * parsed and re-emitted — dropping it on save strips that protection on the next device.
   */
  bucket?: string;
  /** Set when this entry has been deleted. Present entries with a stamp are never rendered. */
  deletedAt?: number | null;
}

/**
 * Journal entries are stored one file per entry as `journal_<id>.md`, with metadata in HTML
 * comments so the file stays readable markdown. Written by the Android client; this parses the
 * same shape. Keep in step with DriveRepository.uploadJournalEntry.
 */
function parseJournalFile(id: number, raw: string): JournalEntryDto {
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

function serialiseJournalFile(entry: JournalEntryDto): string {
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
  lines.push(`<!-- updatedAt: ${Date.now()} -->`);
  lines.push("", entry.content);
  return lines.join("\n");
}

/**
 * Lists every file matching a query, following Drive's pagination.
 *
 * Both listings used to take a single page — 100 notes, 200 journal entries — so older items
 * silently vanished from the web app, and were not counted as hidden either, which made it look
 * like they had been deleted rather than truncated.
 */
async function listAllFiles(
  accessToken: string,
  q: string,
  fields: string,
  orderBy?: string
): Promise<drive_v3.Schema$File[]> {
  const drive = getDriveClient(accessToken);
  const out: drive_v3.Schema$File[] = [];
  let pageToken: string | undefined = undefined;
  // A bound, so a pathological folder cannot spin forever; 20 pages is 20,000 files.
  for (let page = 0; page < 20; page++) {
    const res: { data: drive_v3.Schema$FileList } = await drive.files.list({
      q,
      fields: `nextPageToken, ${fields}`,
      orderBy,
      pageSize: 1000,
      spaces: "drive",
      pageToken,
    });
    out.push(...(res.data.files ?? []));
    pageToken = res.data.nextPageToken ?? undefined;
    if (!pageToken) break;
  }
  return out;
}

export async function getJournalEntries(
  accessToken: string
): Promise<JournalEntryDto[]> {
  const drive = getDriveClient(accessToken);
  const folderId = await getSecondBrainFolderId(accessToken);
  const files = await listAllFiles(
    accessToken,
    `name contains 'journal_' and '${esc(folderId)}' in parents and trashed = false`,
    "files(id, name)"
  );

  const entriesRaw = await Promise.all(
    files.map(async (f) => {
      const id = parseInt(
        (f.name ?? "").replace("journal_", "").replace(".md", ""),
        10
      );
      if (!f.id || Number.isNaN(id)) return null;
      try {
        const contentRes = await drive.files.get(
          { fileId: f.id, alt: "media" },
          { responseType: "text" }
        );
        return parseJournalFile(id, contentRes.data as string);
      } catch {
        // One unreadable file must not empty the whole journal.
        return null;
      }
    })
  );

  return entriesRaw
    .filter((e): e is JournalEntryDto => e !== null)
    // A file carrying a deletedAt stamp is in the 90-day recycle bin, not the journal. The
    // phone shows those in its own Recently Deleted screen; here they are simply gone.
    .filter((e) => e.deletedAt == null)
    .sort((a, b) => b.createdAt - a.createdAt);
}

export async function saveJournalEntry(
  accessToken: string,
  entry: JournalEntryDto
): Promise<void> {
  const folderId = await getSecondBrainFolderId(accessToken);
  const filename = `journal_${entry.id}.md`;
  await writeFile(accessToken, folderId, filename, serialiseJournalFile(entry));
}

export async function deleteJournalEntry(
  accessToken: string,
  id: number
): Promise<void> {
  const drive = getDriveClient(accessToken);
  const folderId = await getSecondBrainFolderId(accessToken);
  const res = await drive.files.list({
    q: `name = 'journal_${id}.md' and '${esc(folderId)}' in parents and trashed = false`,
    fields: "files(id)",
  });
  const fileId = res.data.files?.[0]?.id;
  if (!fileId) return;

  // Marked, not deleted. This used to be a hard files.delete — unrecoverable, and undone anyway
  // by the phone re-uploading its own copy. See deleteNote for the full reasoning.
  const contentRes = await drive.files.get(
    { fileId, alt: "media" },
    { responseType: "text" }
  );
  const raw = contentRes.data as string;
  const withoutMarkers = raw
    .replace(/<!--\s*deletedAt:[^\n]*?-->\n?/g, "")
    .replace(/<!--\s*updatedAt:[^\n]*?-->\n?/g, "");
  const stamped =
    `<!-- deletedAt: ${Date.now()} -->\n<!-- updatedAt: ${Date.now()} -->\n${withoutMarkers}`;

  await drive.files.update({
    fileId,
    media: { mimeType: "text/markdown", body: stamped },
  });
}

// ─── Journal templates ─────────────────────────────────────────────────────────

/** One question on a template. Mirrors TemplateField on the phone; only what is rendered. */
export interface TemplateFieldDto {
  id: string;
  label: string;
  type: string;
  min?: number;
  max?: number;
  minAnchor?: string;
  maxAnchor?: string;
  inlineOptions?: string[];
}

export interface JournalTemplateDto {
  name: string;
  isPrivateByDefault: boolean;
  sortOrder: number;
  bucketName: string;
  fields: TemplateFieldDto[];
}

/**
 * Reads journal_templates.json, as the phone publishes it.
 *
 * The fields arrive as a JSON string *inside* the JSON — `fieldsJson` — because that is how the
 * phone stores them on the template row. Decoded here so callers get a real array; a template
 * whose fields will not parse is returned with none rather than dropped, since its name is
 * still worth showing.
 */
export async function getJournalTemplates(
  accessToken: string
): Promise<JournalTemplateDto[]> {
  const folderId = await getSecondBrainFolderId(accessToken);
  const file = await readFileByName(accessToken, folderId, "journal_templates.json");
  if (!file) return [];

  try {
    const parsed = JSON.parse(file.content);
    const templates = Array.isArray(parsed?.templates) ? parsed.templates : [];
    return templates.map((t: Record<string, unknown>) => {
      let fields: TemplateFieldDto[] = [];
      try {
        const decoded = JSON.parse((t.fieldsJson as string) ?? "[]");
        if (Array.isArray(decoded)) fields = decoded;
      } catch {
        // Keep the template, lose the questions.
      }
      return {
        name: (t.name as string) ?? "",
        isPrivateByDefault: t.isPrivateByDefault === true,
        sortOrder: (t.sortOrder as number) ?? 0,
        bucketName: (t.bucketName as string) ?? "",
        fields,
      };
    });
  } catch {
    return [];
  }
}

// ─── Bucket config ─────────────────────────────────────────────────────────────

export interface BucketSyncDto {
  name: string;
  isVault: boolean;
}

/**
 * Reads the bucket list the Android client publishes to buckets.json.
 *
 * The web app used to fall back to a hardcoded VAULT_BUCKETS list in types.ts, so a bucket
 * Carl marked vault on his phone was rendered here like any other. This reads the real
 * config instead.
 *
 * Returns null when the file is absent or unreadable. Callers MUST treat null as "cannot
 * determine what is private" and fall back to the conservative default — hiding the buckets
 * known to be sensitive — rather than showing everything. Failing open here would leak
 * exactly the content the vault exists to protect.
 */
export async function getBucketConfig(
  accessToken: string
): Promise<BucketSyncDto[] | null> {
  try {
    const folderId = await getSecondBrainFolderId(accessToken);
    const file = await readFileByName(accessToken, folderId, "buckets.json");
    if (!file) return null;
    const parsed = JSON.parse(file.content);
    if (!Array.isArray(parsed)) return null;
    return parsed.filter(
      (b) => typeof b?.name === "string" && typeof b?.isVault === "boolean"
    );
  } catch {
    return null;
  }
}

/**
 * Names of buckets to hide while the vault is locked.
 *
 * Falls back to the hardcoded defaults when buckets.json is missing — the phone may not have
 * synced yet, and on a fresh install that must not mean "nothing is private".
 */
export async function getVaultBucketNames(
  accessToken: string
): Promise<string[]> {
  const config = await getBucketConfig(accessToken);
  if (config === null) {
    const { VAULT_BUCKETS } = await import("./types");
    return VAULT_BUCKETS;
  }
  return config.filter((b) => b.isVault).map((b) => b.name);
}

// ─── File read helpers ─────────────────────────────────────────────────────────

async function readFileByName(
  accessToken: string,
  folderId: string,
  filename: string
): Promise<{ id: string; content: string } | null> {
  const drive = getDriveClient(accessToken);

  const res = await drive.files.list({
    q: `name = '${esc(filename)}' and '${esc(folderId)}' in parents and trashed = false`,
    fields: "files(id, name)",
    spaces: "drive",
  });

  if (!res.data.files || res.data.files.length === 0) return null;

  const fileId = res.data.files[0].id!;
  const contentRes = await drive.files.get(
    { fileId, alt: "media" },
    { responseType: "text" }
  );

  return { id: fileId, content: contentRes.data as string };
}

async function writeFile(
  accessToken: string,
  folderId: string,
  filename: string,
  content: string,
  existingFileId?: string
): Promise<string> {
  const drive = getDriveClient(accessToken);
  const media = { mimeType: "text/plain", body: content };

  if (existingFileId) {
    await drive.files.update({
      fileId: existingFileId,
      media,
    });
    return existingFileId;
  }

  // Check if file already exists
  const existing = await drive.files.list({
    q: `name = '${esc(filename)}' and '${esc(folderId)}' in parents and trashed = false`,
    fields: "files(id)",
  });

  if (existing.data.files && existing.data.files.length > 0) {
    const fileId = existing.data.files[0].id!;
    await drive.files.update({ fileId, media });
    return fileId;
  }

  const created = await drive.files.create({
    requestBody: { name: filename, parents: [folderId] },
    media,
    fields: "id",
  });
  return created.data.id!;
}

// ─── API Key / Settings ────────────────────────────────────────────────────────

export async function getApiKey(accessToken: string): Promise<string | null> {
  const folderId = await getSecondBrainFolderId(accessToken);
  const file = await readFileByName(accessToken, folderId, "settings.json");
  if (!file) return null;
  try {
    const settings = JSON.parse(file.content);
    return settings.apiKey ?? null;
  } catch {
    return null;
  }
}

export async function saveApiKey(
  accessToken: string,
  apiKey: string
): Promise<void> {
  const folderId = await getSecondBrainFolderId(accessToken);
  // Read existing settings first so we don't overwrite the OpenAI key
  const existing = await readFileByName(accessToken, folderId, "settings.json");
  let settings: Record<string, string> = {};
  if (existing) {
    try { settings = JSON.parse(existing.content); } catch { /* ignore */ }
  }
  settings.apiKey = apiKey;
  await writeFile(
    accessToken,
    folderId,
    "settings.json",
    JSON.stringify(settings, null, 2)
  );
}

export async function getOpenaiApiKey(accessToken: string): Promise<string | null> {
  const folderId = await getSecondBrainFolderId(accessToken);
  const file = await readFileByName(accessToken, folderId, "settings.json");
  if (!file) return null;
  try {
    const settings = JSON.parse(file.content);
    return settings.openaiApiKey ?? null;
  } catch {
    return null;
  }
}

export async function saveOpenaiApiKey(accessToken: string, openaiApiKey: string): Promise<void> {
  const folderId = await getSecondBrainFolderId(accessToken);
  // Read existing settings first so we don't overwrite the Anthropic key
  const existing = await readFileByName(accessToken, folderId, "settings.json");
  let settings: Record<string, string> = {};
  if (existing) {
    try { settings = JSON.parse(existing.content); } catch { /* ignore */ }
  }
  settings.openaiApiKey = openaiApiKey;
  await writeFile(accessToken, folderId, "settings.json", JSON.stringify(settings, null, 2));
}

// ─── Memory ────────────────────────────────────────────────────────────────────

/**
 * memory.md, with the Drive modification time that identifies this revision.
 *
 * The stamp is what makes a safe write possible: the phone appends to this file whenever it
 * learns something, so a web save that does not check first silently erases whatever was added
 * since the page loaded. Drive's own modifiedTime is used rather than a marker inside the file,
 * because the content is fed verbatim into every Claude call on both clients and does not want
 * bookkeeping in it.
 */
export async function getMemoryWithVersion(
  accessToken: string
): Promise<{ content: string; modifiedTime: string }> {
  const drive = getDriveClient(accessToken);
  const folderId = await getSecondBrainFolderId(accessToken);
  const res = await drive.files.list({
    q: `name = 'memory.md' and '${esc(folderId)}' in parents and trashed = false`,
    fields: "files(id, modifiedTime)",
    spaces: "drive",
  });
  const file = res.data.files?.[0];
  // No file: return empty rather than seeding one.
  //
  // The seed this used to write described Carl as a Deputy of NSW SES, which he has not been
  // since his role changed — and memory.md is prepended to every Claude call, so a single web
  // page-load could poison the context on both clients. The phone owns the initial seed
  // (DriveRepository.INITIAL_MEMORY), which is kept in step with CLAUDE.md.
  if (!file?.id) return { content: "", modifiedTime: "" };

  const contentRes = await drive.files.get(
    { fileId: file.id, alt: "media" },
    { responseType: "text" }
  );
  return {
    content: contentRes.data as string,
    modifiedTime: file.modifiedTime ?? "",
  };
}

export async function getMemory(accessToken: string): Promise<string> {
  return (await getMemoryWithVersion(accessToken)).content;
}

/**
 * Writes memory.md, refusing when it has changed since [baseModifiedTime].
 *
 * The phone appends to this file on its own schedule — every capture can add a line — so a
 * blind write from the laptop erases whatever it learned in the meantime, and the phone's next
 * append erases the laptop's edit right back. Neither side noticed.
 *
 * @returns false when the file moved on and the caller should reload. Passing an empty
 *   baseModifiedTime skips the check, for callers that genuinely mean "overwrite".
 */
export async function updateMemory(
  accessToken: string,
  content: string,
  baseModifiedTime = ""
): Promise<boolean> {
  const folderId = await getSecondBrainFolderId(accessToken);
  if (baseModifiedTime) {
    const current = await getMemoryWithVersion(accessToken);
    if (current.modifiedTime && current.modifiedTime !== baseModifiedTime) return false;
  }
  await writeFile(accessToken, folderId, "memory.md", content);
  return true;
}

// ─── Todos ─────────────────────────────────────────────────────────────────────

export async function getTodos(accessToken: string): Promise<TodoSyncDto[]> {
  const folderId = await getSecondBrainFolderId(accessToken);
  const file = await readFileByName(accessToken, folderId, "todos.json");
  if (!file) return [];
  try {
    const all: TodoSyncDto[] = JSON.parse(file.content);
    return all.filter((t) => t.deletedAt == null);
  } catch {
    return [];
  }
}

export async function saveTodos(
  accessToken: string,
  todos: TodoSyncDto[]
): Promise<void> {
  const folderId = await getSecondBrainFolderId(accessToken);
  await writeFile(
    accessToken,
    folderId,
    "todos.json",
    JSON.stringify(todos, null, 2)
  );
}

// ─── Notes ─────────────────────────────────────────────────────────────────────

function parseNoteFile(id: string, content: string): NoteDto {
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

export async function getNotes(accessToken: string): Promise<NoteDto[]> {
  const drive = getDriveClient(accessToken);
  const folderId = await getSecondBrainFolderId(accessToken);

  const files = await listAllFiles(
    accessToken,
    `name contains 'note_' and '${esc(folderId)}' in parents and trashed = false`,
    "files(id, name, createdTime, modifiedTime)",
    "modifiedTime desc"
  );

  if (files.length === 0) return [];

  const notes = await Promise.all(
    files.map(async (f) => {
      // Per-file, like the journal loader: one unreadable file — a transient 500, a
      // permissions hiccup — used to reject the whole Promise.all, so the route returned 500
      // and the Notes screen showed "Failed to load notes" with everything hidden.
      try {
        if (!f.id || !f.name) return null;
        const contentRes = await drive.files.get(
          { fileId: f.id, alt: "media" },
          { responseType: "text" }
        );
        const rawId = f.name.replace("note_", "").replace(".md", "");
        const note = parseNoteFile(rawId, contentRes.data as string);
        note.driveFileId = f.id;
        note.createdAt = f.createdTime
          ? new Date(f.createdTime).getTime()
          : undefined;
        note.updatedAt = f.modifiedTime
          ? new Date(f.modifiedTime).getTime()
          : undefined;
        return note;
      } catch {
        return null;
      }
    })
  );

  // Deleted notes are withheld here rather than filtered by each caller — the same reasoning
  // as vault filtering: a list that forgets to filter is how the wrong thing gets shown.
  return notes.filter((n): n is NoteDto => n !== null && n.deletedAt == null);
}

export async function saveNote(
  accessToken: string,
  id: string,
  title: string,
  content: string,
  bucket: string,
  attachments = ""
): Promise<void> {
  const folderId = await getSecondBrainFolderId(accessToken);
  // The updatedAt stamp is what tells the phone this copy is newer than the one it holds.
  // Without it the Android merge cannot compare, and an edit made here stays on Drive being
  // ignored until the phone's next push overwrites it.
  // A blank bucket is written as no comment at all, rather than as an empty or invented one.
  // The note stays "unknown bucket" — which the notes route withholds while the vault is
  // locked — instead of being silently relabelled into a public bucket by an edit.
  const bucketLine = bucket.trim() ? `<!-- bucket: ${bucket.trim()} -->\n` : "";
  // Echoed back rather than dropped. The web app has no attachment UI, but a note edited here
  // must not lose the photos added on the phone — the files would stay in Drive with nothing
  // referencing them.
  const attachmentLine = attachments.trim()
    ? `<!-- attachments: ${attachments.trim()} -->\n`
    : "";
  const fileContent =
    `# ${title}\n${bucketLine}${attachmentLine}<!-- updatedAt: ${Date.now()} -->\n\n${content}`;
  await writeFile(accessToken, folderId, `note_${id}.md`, fileContent);
}

/**
 * Deletes a note by marking the file, not by trashing it.
 *
 * Trashing did not work. The phone treats a synced note whose Drive file has vanished as a lost
 * upload — the case where a folder was consolidated out from under it — and re-uploads from its
 * own copy, so a note deleted on the laptop reappeared within fifteen minutes with nothing in
 * the UI to explain it.
 *
 * A `deletedAt` stamp says "deliberately deleted" in a way the phone can act on: it soft-deletes,
 * which puts the note in Recently Deleted and keeps it recoverable for 90 days, exactly as a
 * delete on the phone does. The phone's own midnight cleanup removes the file for good at the end
 * of that window. Same shape todos.json has always used.
 */
export async function deleteNote(
  accessToken: string,
  id: string
): Promise<void> {
  const drive = getDriveClient(accessToken);
  const folderId = await getSecondBrainFolderId(accessToken);

  const res = await drive.files.list({
    q: `name = 'note_${esc(String(id))}.md' and '${esc(folderId)}' in parents and trashed = false`,
    fields: "files(id)",
  });
  const fileId = res.data.files?.[0]?.id;
  if (!fileId) return;

  const contentRes = await drive.files.get(
    { fileId, alt: "media" },
    { responseType: "text" }
  );
  const raw = contentRes.data as string;
  // Re-stamped rather than appended to, so deleting twice cannot stack markers.
  const withoutMarkers = raw
    .replace(/<!--\s*deletedAt:[^\n]*?-->\n?/g, "")
    .replace(/<!--\s*updatedAt:[^\n]*?-->\n?/g, "");
  const stamped =
    `<!-- deletedAt: ${Date.now()} -->\n<!-- updatedAt: ${Date.now()} -->\n${withoutMarkers}`;

  await drive.files.update({
    fileId,
    media: { mimeType: "text/markdown", body: stamped },
  });
}

// ─── Meetings ──────────────────────────────────────────────────────────────────

/** Find or create the meetings/ subfolder under SecondBrain */
export async function getMeetingsFolderId(
  accessToken: string,
  secondBrainFolderId: string
): Promise<string> {
  const drive = getDriveClient(accessToken);

  const res = await drive.files.list({
    q: `name = 'meetings' and mimeType = 'application/vnd.google-apps.folder' and '${esc(secondBrainFolderId)}' in parents and trashed = false`,
    fields: "files(id, name)",
    spaces: "drive",
  });

  if (res.data.files && res.data.files.length > 0) {
    return res.data.files[0].id!;
  }

  const created = await drive.files.create({
    requestBody: {
      name: "meetings",
      mimeType: "application/vnd.google-apps.folder",
      parents: [secondBrainFolderId],
    },
    fields: "id",
  });

  return created.data.id!;
}

/** List all meeting subfolders under the meetings folder */
export async function listMeetingFolders(
  accessToken: string,
  meetingsFolderId: string
): Promise<{ id: string; name: string; modifiedTime: string }[]> {
  const drive = getDriveClient(accessToken);

  const res = await drive.files.list({
    q: `mimeType = 'application/vnd.google-apps.folder' and '${esc(meetingsFolderId)}' in parents and trashed = false`,
    fields: "files(id, name, modifiedTime)",
    orderBy: "modifiedTime desc",
    pageSize: 100,
    spaces: "drive",
  });

  return (res.data.files ?? []).map((f) => ({
    id: f.id!,
    name: f.name!,
    modifiedTime: f.modifiedTime ?? new Date().toISOString(),
  }));
}

/** Read a named file from a folder, returns null if not found */
export async function readFileFromFolder(
  accessToken: string,
  folderId: string,
  filename: string
): Promise<string | null> {
  const result = await readFileByName(accessToken, folderId, filename);
  return result ? result.content : null;
}

/** Create a meeting folder and upload transcript.md + summary.md */
export async function createMeetingFolder(
  accessToken: string,
  meetingsFolderId: string,
  folderName: string,
  transcriptMd: string,
  summaryMd: string
): Promise<string> {
  const drive = getDriveClient(accessToken);

  // Create the folder
  const folder = await drive.files.create({
    requestBody: {
      name: folderName,
      mimeType: "application/vnd.google-apps.folder",
      parents: [meetingsFolderId],
    },
    fields: "id",
  });

  const folderId = folder.data.id!;

  // Upload transcript.md and summary.md in parallel
  await Promise.all([
    writeFile(accessToken, folderId, "transcript.md", transcriptMd),
    writeFile(accessToken, folderId, "summary.md", summaryMd),
  ]);

  return folderId;
}

/** Update existing meeting files (only updates files that are provided) */
/**
 * Writes the parts of a meeting the web app can edit.
 *
 * `actionsJson` and the meta merge matter as much as the markdown. Action items now live in
 * actions.json — the phone reads them from there — so approving or editing one here has to
 * write that file, not just re-embed [ACTION:] markers in the summary the phone ignores. And
 * meta.json has to be merged rather than replaced: it carries the recording time, duration and
 * bucket, none of which the web app knows, and rewriting it blind would blank the bucket that
 * decides whether the meeting is vault.
 */
export async function updateMeetingFiles(
  accessToken: string,
  folderId: string,
  transcriptMd?: string,
  summaryMd?: string,
  actionsJson?: string,
  metaPatch?: Record<string, unknown>
): Promise<void> {
  const updates: Promise<string>[] = [];
  if (transcriptMd !== undefined) {
    updates.push(writeFile(accessToken, folderId, "transcript.md", transcriptMd));
  }
  if (summaryMd !== undefined) {
    updates.push(writeFile(accessToken, folderId, "summary.md", summaryMd));
  }
  if (actionsJson !== undefined) {
    updates.push(writeFile(accessToken, folderId, "actions.json", actionsJson));
  }
  if (metaPatch) {
    const existingRaw = await readFileFromFolder(accessToken, folderId, "meta.json");
    let existing: Record<string, unknown> = {};
    if (existingRaw) {
      try {
        const parsed = JSON.parse(existingRaw);
        if (parsed && typeof parsed === "object") existing = parsed;
      } catch {
        // Malformed: treat as empty rather than refusing the edit.
      }
    }
    const merged = { ...existing, ...metaPatch };
    updates.push(
      writeFile(accessToken, folderId, "meta.json", JSON.stringify(merged, null, 2))
    );
  }
  await Promise.all(updates);
}
