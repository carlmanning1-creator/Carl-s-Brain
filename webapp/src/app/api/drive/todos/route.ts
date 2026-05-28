import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getTodos, saveTodos } from "@/lib/drive";
import type { TodoSyncDto } from "@/lib/types";

export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const todos = await getTodos(session.accessToken);
    return NextResponse.json({ todos });
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

    // Load existing todos (including soft-deleted, so we don't lose them)
    const allTodos = await getAllTodosRaw(session.accessToken);

    const now = Date.now();
    const existingIndex = allTodos.findIndex((t) => t.id === incoming.id);

    if (existingIndex >= 0) {
      // Update existing
      allTodos[existingIndex] = { ...incoming, updatedAt: now };
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
