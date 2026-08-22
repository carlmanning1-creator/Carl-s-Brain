import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getChatThreads, saveChatThread, deleteChatThread } from "@/lib/drive";
import { validEntityId } from "@/lib/driveQuery";
import type { ChatMessageDto, ChatThreadDto } from "@/lib/fileFormat";

/**
 * Chat conversations, shared with the phone through `/SecondBrain/chat_<id>.md`.
 *
 * Not vault-filtered, and deliberately so: chat is a vault-closed surface on both clients —
 * it files into non-vault buckets only, because its completion path is vault-filtered — so
 * there is no such thing as a vault conversation to withhold. If chat ever gains a privacy
 * flag, the filter belongs in lib/drive.ts alongside the journal's, on the server.
 */

/** GET /api/drive/chat — every conversation, newest first. */
export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }
  try {
    return NextResponse.json({ threads: await getChatThreads(session.accessToken) });
  } catch (err) {
    console.error("GET /api/drive/chat error:", err);
    return NextResponse.json(
      { error: "Failed to fetch conversations" },
      { status: 500 }
    );
  }
}

/**
 * POST /api/drive/chat — saves a whole conversation.
 *
 * The file is the thread, so the request carries every message rather than a delta. That is
 * what makes the two clients agree without reconciling message by message: ids are per-device
 * Room autoincrement values and mean nothing here.
 *
 * A new thread takes its id from the clock, as journal entries do. Android ids are small Room
 * integers and these are epoch milliseconds, so the two sequences cannot collide.
 */
export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const body = await req.json();
    const now = Date.now();

    const rawMessages: unknown = body.messages;
    if (!Array.isArray(rawMessages) || rawMessages.length === 0) {
      // An empty conversation is never published. The phone skips them on its side too — a
      // contentless file would only be something the pull has to special-case.
      return NextResponse.json(
        { error: "messages is required and must be non-empty" },
        { status: 400 }
      );
    }

    const messages: ChatMessageDto[] = rawMessages.map((m, i) => {
      const msg = m as Record<string, unknown>;
      return {
        content: typeof msg.content === "string" ? msg.content : "",
        isFromUser: msg.isFromUser === true || msg.role === "user",
        // Ordering is what makes a conversation legible, so a message with no usable stamp
        // gets one derived from its position rather than all of them collapsing onto `now`.
        createdAt:
          typeof msg.createdAt === "number" && msg.createdAt > 0
            ? msg.createdAt
            : now - (rawMessages.length - i),
      };
    });

    // validEntityId is the same gate every id-taking route uses: an id becomes a filename
    // and part of a Drive query, so anything that is not an integer this app produced is
    // refused rather than interpolated.
    const id = Number(validEntityId(String(body.id ?? ""))) || now;
    const thread: ChatThreadDto = {
      id,
      title:
        typeof body.title === "string" && body.title.trim()
          ? body.title.trim().slice(0, 50)
          : // Same rule the phone uses: the first thing Carl said names the conversation.
            (messages.find((m) => m.isFromUser)?.content ?? "New conversation")
              .slice(0, 50)
              .trimEnd(),
      createdAt:
        typeof body.createdAt === "number" && body.createdAt > 0
          ? body.createdAt
          : now,
      updatedAt: now,
      messages,
    };

    await saveChatThread(session.accessToken, thread);
    return NextResponse.json({ ok: true, id: thread.id });
  } catch (err) {
    console.error("POST /api/drive/chat error:", err);
    return NextResponse.json(
      { error: "Failed to save conversation" },
      { status: 500 }
    );
  }
}

/**
 * DELETE /api/drive/chat?id=…
 *
 * Stamps the file rather than removing it — the phone reads a synced thread whose Drive file
 * has vanished as a lost upload and re-uploads its own copy, so a hard delete would come back
 * within fifteen minutes.
 */
export async function DELETE(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }
  // An id becomes both a filename and part of a Drive query, so a crafted one could otherwise
  // be pointed at a file this route was never asked about.
  const idStr = validEntityId(req.nextUrl.searchParams.get("id"));
  if (!idStr) {
    return NextResponse.json({ error: "Invalid id" }, { status: 400 });
  }
  const id = Number(idStr);
  try {
    await deleteChatThread(session.accessToken, id);
    return NextResponse.json({ ok: true });
  } catch (err) {
    console.error("DELETE /api/drive/chat error:", err);
    return NextResponse.json(
      { error: "Failed to delete conversation" },
      { status: 500 }
    );
  }
}
