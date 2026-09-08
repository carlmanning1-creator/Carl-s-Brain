import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { google } from "googleapis";
import { getTodos, saveTodos, getVaultBucketNames, getSecondBrainFolderId } from "@/lib/drive";
import { TODO_SCHEMA_VERSION, type TodoSyncDto } from "@/lib/types";
import {
  escapeDriveQueryValue as esc,
  isVaultBucket,
  validEntityId,
} from "@/lib/driveQuery";
import { spawnNextOccurrence } from "@/lib/recurrence";

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
    // Through the shared helper: this used an exact Array.includes while six other vault
    // gates matched case-insensitively, so a bucket whose case differed between todos.json
    // and buckets.json slipped straight through the filter.
    const visible = todos.filter((t) => !isVaultBucket(t.bucket, vaultBuckets));
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

export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const body = await req.json();
    const incoming: TodoSyncDto = body.todo;

    // Validated on the way in, like the sibling notes and chat routes.
    //
    // A non-numeric id used to be written straight into todos.json, and the phone parses that
    // file into TodoSyncDto where `id` is a Long. One bad row throws, the getOrElse around the
    // parse swallows it, and the phone's entire to-do sync stops — permanently, because nothing
    // ever rewrites the file. An absent id is still allowed: that is a create.
    if (incoming.id != null && !validEntityId(String(incoming.id))) {
      return NextResponse.json({ error: "Invalid todo id" }, { status: 400 });
    }

    // Load existing todos (including soft-deleted, so we don't lose them)
    const allTodos = await getAllTodosRaw(session.accessToken);

    const now = Date.now();
    const existingIndex = allTodos.findIndex((t) => t.id === incoming.id);

    // The vault rule applies to writes and to what is echoed back, not only to GET.
    //
    // GET is carefully filtered server-side; this handler had no vault check anywhere, and it
    // returns the merged row — so a request naming a vault to-do's id echoed its title, bucket
    // and due date with the vault closed, and could move a to-do into or out of a vault bucket.
    // Fails closed: 404 rather than 403, so the response does not confirm the row exists.
    const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
    if (!vaultOpen) {
      const vaultBuckets = await getVaultBucketNames(session.accessToken);
      if (
        existingIndex >= 0 &&
        isVaultBucket(allTodos[existingIndex].bucket, vaultBuckets)
      ) {
        return NextResponse.json({ error: "Not found" }, { status: 404 });
      }
      if (incoming.bucket && isVaultBucket(incoming.bucket, vaultBuckets)) {
        return NextResponse.json(
          { error: "That bucket is not available" },
          { status: 403 }
        );
      }
    }

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
        // Stamped on every write. The merge above means this row now carries every field this
        // client knows plus everything it does not understand, which is exactly the promise v2
        // makes to the phone: a null here is a deliberate clear, not ignorance.
        schema: TODO_SCHEMA_VERSION,
      };
    } else {
      // Create new with generated id if needed
      const newTodo: TodoSyncDto = {
        ...incoming,
        id: incoming.id || Date.now(),
        createdAt: incoming.createdAt || now,
        updatedAt: now,
        schema: TODO_SCHEMA_VERSION,
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
  // One static import instead of three dynamic ones (plus an unused binding) inside a hot path.
  const folderId = await getSecondBrainFolderId(accessToken);

  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  const drive = google.drive({ version: "v3", auth });

  const res = await drive.files.list({
    // esc(), like every other query in the codebase. Contained today — the id comes from Drive
    // — but this was the one place that broke the rule lib/driveQuery.ts exists to enforce.
    q: `name = 'todos.json' and '${esc(folderId)}' in parents and trashed = false`,
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
