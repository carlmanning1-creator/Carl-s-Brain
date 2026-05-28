import { google } from "googleapis";
import type { TodoSyncDto, NoteDto } from "./types";

// ─── Auth helper ───────────────────────────────────────────────────────────────

function getDriveClient(accessToken: string) {
  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  return google.drive({ version: "v3", auth });
}

// ─── Folder helpers ────────────────────────────────────────────────────────────

export async function getSecondBrainFolderId(
  accessToken: string
): Promise<string> {
  const drive = getDriveClient(accessToken);

  // Search for existing SecondBrain folder
  const res = await drive.files.list({
    q: "name = 'SecondBrain' and mimeType = 'application/vnd.google-apps.folder' and trashed = false and 'root' in parents",
    fields: "files(id, name)",
    spaces: "drive",
  });

  if (res.data.files && res.data.files.length > 0) {
    return res.data.files[0].id!;
  }

  // Create if not found
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
  await writeFile(
    accessToken,
    folderId,
    "settings.json",
    JSON.stringify({ apiKey }, null, 2)
  );
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
    bodyStart = lines[1] === "" ? 2 : 1;
  }

  // Extract bucket from filename prefix or first metadata line
  let bucket = "Personal";
  const metaMatch = content.match(/^<!--\s*bucket:\s*(.+?)\s*-->/m);
  if (metaMatch) bucket = metaMatch[1];

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
  const fileContent = `# ${title}\n<!-- bucket: ${bucket} -->\n\n${content}`;
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
