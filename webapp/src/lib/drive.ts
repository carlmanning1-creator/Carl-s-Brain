import { google } from "googleapis";
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

export async function getJournalEntries(
  accessToken: string
): Promise<JournalEntryDto[]> {
  const drive = getDriveClient(accessToken);
  const folderId = await getSecondBrainFolderId(accessToken);
  const res = await drive.files.list({
    q: `name contains 'journal_' and '${folderId}' in parents and trashed = false`,
    fields: "files(id, name)",
    pageSize: 200,
  });
  const files = res.data.files ?? [];

  const entries = await Promise.all(
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

  return entries
    .filter((e): e is JournalEntryDto => e !== null)
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
    q: `name = 'journal_${id}.md' and '${folderId}' in parents and trashed = false`,
    fields: "files(id)",
  });
  const fileId = res.data.files?.[0]?.id;
  if (fileId) await drive.files.delete({ fileId });
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
    q: `name = '${filename}' and '${folderId}' in parents and trashed = false`,
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
    q: `name = '${filename}' and '${folderId}' in parents and trashed = false`,
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

export async function getMemory(accessToken: string): Promise<string> {
  const folderId = await getSecondBrainFolderId(accessToken);
  const file = await readFileByName(accessToken, folderId, "memory.md");
  if (!file) {
    // Seed initial memory
    const seed = `# Carl's Brain — Memory Context

## About Carl
- Carl Manning
- Deputy, NSW SES (State Emergency Service) — Dubbo Unit
- Uses this app as his external memory and ADHD support tool

## Life Buckets
- SES — State Emergency Service work and volunteering
- Family — family matters and relationships
- Work — professional tasks
- Personal — personal goals and interests
- Kink — private/personal (vault bucket)
- Other — miscellaneous

## Instructions for Claude
- Prioritise clarity and brevity — Carl has ADHD
- Be proactive about surfacing urgent items
- Remember context across conversations
- Help break down complex tasks into small steps
- Use Australian English spelling
`;
    await writeFile(accessToken, folderId, "memory.md", seed);
    return seed;
  }
  return file.content;
}

export async function updateMemory(
  accessToken: string,
  content: string
): Promise<void> {
  const folderId = await getSecondBrainFolderId(accessToken);
  await writeFile(accessToken, folderId, "memory.md", content);
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

  return {
    id,
    title,
    content: lines.slice(bodyStart).join("\n").trim(),
    bucket,
  };
}

export async function getNotes(accessToken: string): Promise<NoteDto[]> {
  const drive = getDriveClient(accessToken);
  const folderId = await getSecondBrainFolderId(accessToken);

  const res = await drive.files.list({
    q: `name contains 'note_' and '${folderId}' in parents and trashed = false`,
    fields: "files(id, name, createdTime, modifiedTime)",
    orderBy: "modifiedTime desc",
    pageSize: 100,
    spaces: "drive",
  });

  if (!res.data.files || res.data.files.length === 0) return [];

  const notes = await Promise.all(
    res.data.files.map(async (f) => {
      const contentRes = await drive.files.get(
        { fileId: f.id!, alt: "media" },
        { responseType: "text" }
      );
      const rawId = f.name!.replace("note_", "").replace(".md", "");
      const note = parseNoteFile(rawId, contentRes.data as string);
      note.driveFileId = f.id!;
      note.createdAt = f.createdTime
        ? new Date(f.createdTime).getTime()
        : undefined;
      note.updatedAt = f.modifiedTime
        ? new Date(f.modifiedTime).getTime()
        : undefined;
      return note;
    })
  );

  return notes;
}

export async function saveNote(
  accessToken: string,
  id: string,
  title: string,
  content: string,
  bucket: string
): Promise<void> {
  const folderId = await getSecondBrainFolderId(accessToken);
  // The updatedAt stamp is what tells the phone this copy is newer than the one it holds.
  // Without it the Android merge cannot compare, and an edit made here stays on Drive being
  // ignored until the phone's next push overwrites it.
  // A blank bucket is written as no comment at all, rather than as an empty or invented one.
  // The note stays "unknown bucket" — which the notes route withholds while the vault is
  // locked — instead of being silently relabelled into a public bucket by an edit.
  const bucketLine = bucket.trim() ? `<!-- bucket: ${bucket.trim()} -->\n` : "";
  const fileContent =
    `# ${title}\n${bucketLine}<!-- updatedAt: ${Date.now()} -->\n\n${content}`;
  await writeFile(accessToken, folderId, `note_${id}.md`, fileContent);
}

export async function deleteNote(
  accessToken: string,
  id: string
): Promise<void> {
  const drive = getDriveClient(accessToken);
  const folderId = await getSecondBrainFolderId(accessToken);

  const res = await drive.files.list({
    q: `name = 'note_${id}.md' and '${folderId}' in parents and trashed = false`,
    fields: "files(id)",
  });

  if (res.data.files && res.data.files.length > 0) {
    await drive.files.update({
      fileId: res.data.files[0].id!,
      requestBody: { trashed: true },
    });
  }
}

// ─── Meetings ──────────────────────────────────────────────────────────────────

/** Find or create the meetings/ subfolder under SecondBrain */
export async function getMeetingsFolderId(
  accessToken: string,
  secondBrainFolderId: string
): Promise<string> {
  const drive = getDriveClient(accessToken);

  const res = await drive.files.list({
    q: `name = 'meetings' and mimeType = 'application/vnd.google-apps.folder' and '${secondBrainFolderId}' in parents and trashed = false`,
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
    q: `mimeType = 'application/vnd.google-apps.folder' and '${meetingsFolderId}' in parents and trashed = false`,
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
export async function updateMeetingFiles(
  accessToken: string,
  folderId: string,
  transcriptMd?: string,
  summaryMd?: string
): Promise<void> {
  const updates: Promise<string>[] = [];
  if (transcriptMd !== undefined) {
    updates.push(writeFile(accessToken, folderId, "transcript.md", transcriptMd));
  }
  if (summaryMd !== undefined) {
    updates.push(writeFile(accessToken, folderId, "summary.md", summaryMd));
  }
  await Promise.all(updates);
}
