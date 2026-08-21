import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getTodos, saveTodos, getVaultBucketNames } from "@/lib/drive";
import type { TodoSyncDto } from "@/lib/types";

/**
 * GET /api/drive/todos?vault=open
 *
 * Vault filtering happens HERE, on the server, not in the browser. The previous behaviour
 * sent every to-do to the page and hid the vault ones with a client-side filter, so the
 * content was in the response, in the browser's memory and in devtools regardless of whether
 * the vault was unlocked. On a shared or work machine that is the opposite of what the vault
 * is for.
 *
 * The bucket list also now comes from buckets.json, published by the phone, rather than a
 * hardcoded list — so a bucket Carl marks vault on the phone is honoured here too.
 */
export async function GET(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
    const todos = await getTodos(session.accessToken);
    if (vaultOpen) return NextResponse.json({ todos });

    const vaultBuckets = await getVaultBucketNames(session.accessToken);
    const visible = todos.filter((t) => !vaultBuckets.includes(t.bucket));
    // hiddenCount lets the UI say "3 hidden in the vault" without naming any of them.
    return NextResponse.json({
      todos: visible,
      hiddenCount: todos.length - visible.length,
    });
  } catch (err) {
    console.error("GET /api/drive/todos error:", err);
    return NextResponse.json(
      { error: "Failed to fetch todos" },
      { status: 500 }
    );
  }
}

/**
 * The next due date for a recurring to-do, mirroring CompleteTodoUseCase.nextDateMs on the
 * phone. Kept deliberately in step with it: two implementations of recurrence that disagree
 * produce either a missing occurrence or a duplicate, and both fail silently.
 */
function nextDueDate(
  baseMs: number | null,
  recurrence: NonNullable<TodoSyncDto["recurrence"]>
): number | null {
  const DAY = 24 * 60 * 60 * 1000;
  const from = baseMs ?? Date.now();
  switch (recurrence) {
    case "DAILY":
      return from + DAY;
    case "WEEKLY":
      return from + 7 * DAY;
    case "FORTNIGHTLY":
      return from + 14 * DAY;
    case "MONTHLY": {
      const d = new Date(from);
      d.setMonth(d.getMonth() + 1);
      return d.getTime();
    }
    default:
      return null;
  }
}

/**
 * Spawns the next occurrence when a recurring to-do is ticked off, the way the phone does.
 *
 * Without this, ticking a weekly task from the laptop ended the chain silently: the phone's
 * pull applies the update directly and never runs the completion use case, so nothing anywhere
 * created the next one.
 *
 * @returns the occurrence to append, or null when nothing should be spawned.
 */
function spawnNextOccurrence(
  completed: TodoSyncDto,
  all: TodoSyncDto[],
  now: number
): TodoSyncDto | null {
  const recurrence = completed.recurrence;
  if (!recurrence) return null;

  // Idempotency, matching findActiveRecurringByTitleAndRecurrence on the phone: if an open
  // occurrence of the same task already exists, a second tick must not add another.
  const alreadyOpen = all.some(
    (t) =>
      !t.isDone &&
      t.deletedAt === null &&
      t.id !== completed.id &&
      t.title === completed.title &&
      t.recurrence === recurrence
  );
  if (alreadyOpen) return null;

  const nextDue = nextDueDate(completed.dueDate, recurrence);
  if (nextDue === null) return null;

  const intervalMs = nextDue - (completed.dueDate ?? now);
  const leadDays = completed.leadDays ?? 0;
  const nextReminder =
    leadDays > 0
      ? nextDue - leadDays * 24 * 60 * 60 * 1000
      : completed.reminderAt != null
        ? completed.reminderAt + intervalMs
        : null;

  // Epoch-ms ids, the same convention the journal route uses, so a web-created row cannot
  // collide with a Room autoincrement id from the phone. Stepped forward past any id already
  // in use, since the to-do being completed may itself have been created in this same request.
  let spawnId = now;
  while (all.some((t) => t.id === spawnId)) spawnId++;

  return {
    ...completed,
    id: spawnId,
    isDone: false,
    dueDate: nextDue,
    reminderAt: nextReminder,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
  };
}

export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const body = await req.json();
    const incoming: TodoSyncDto = body.todo;

    // Load existing todos (including soft-deleted, so we don't lose them)
    const allTodos = await getAllTodosRaw(session.accessToken);

    const now = Date.now();
    const existingIndex = allTodos.findIndex((t) => t.id === incoming.id);

    // Captured before the merge: spawning depends on this being a transition to done, not on
    // the row already being done and edited again.
    const wasDone = existingIndex >= 0 ? allTodos[existingIndex].isDone : false;

    if (existingIndex >= 0) {
      // MERGE onto the stored entry, never replace it. The editor only sends the fields it
      // knows about, so a straight replace silently dropped everything else the phone had
      // written — reminders, pin state, time estimates. Spreading the existing row first, then
      // the incoming one, keeps unknown fields intact while still applying real edits.
      allTodos[existingIndex] = {
        ...allTodos[existingIndex],
        ...incoming,
        updatedAt: now,
      };
    } else {
      // Create new with generated id if needed
      const newTodo: TodoSyncDto = {
        ...incoming,
        id: incoming.id || Date.now(),
        createdAt: incoming.createdAt || now,
        updatedAt: now,
      };
      allTodos.push(newTodo);
    }

    // Recurrence is handled here rather than in the browser so it applies however the tick
    // arrived — the list checkbox, the editor, or anything added later.
    const saved0 = existingIndex >= 0 ? allTodos[existingIndex] : allTodos[allTodos.length - 1];
    if (!wasDone && saved0.isDone) {
      const next = spawnNextOccurrence(saved0, allTodos, now);
      if (next) allTodos.push(next);
    }

    await saveTodos(session.accessToken, allTodos);

    const saved = allTodos.find((t) => t.id === incoming.id) ?? allTodos[allTodos.length - 1];
    return NextResponse.json({ todo: saved });
  } catch (err) {
    console.error("POST /api/drive/todos error:", err);
    return NextResponse.json(
      { error: "Failed to save todo" },
      { status: 500 }
    );
  }
}

// Internal helper to get all todos including soft-deleted
async function getAllTodosRaw(accessToken: string): Promise<TodoSyncDto[]> {
  const { getSecondBrainFolderId } = await import("@/lib/drive");
  const { google } = await import("googleapis");

  const auth = new (await import("googleapis")).google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  const drive = (await import("googleapis")).google.drive({ version: "v3", auth });

  const folderId = await getSecondBrainFolderId(accessToken);

  const res = await drive.files.list({
    q: `name = 'todos.json' and '${folderId}' in parents and trashed = false`,
    fields: "files(id)",
  });

  if (!res.data.files || res.data.files.length === 0) return [];

  const fileId = res.data.files[0].id!;
  const contentRes = await drive.files.get(
    { fileId, alt: "media" },
    { responseType: "text" }
  );

  try {
    return JSON.parse(contentRes.data as string);
  } catch {
    return [];
  }
}
