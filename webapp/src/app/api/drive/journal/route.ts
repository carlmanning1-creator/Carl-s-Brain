import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import {
  getJournalEntries,
  saveJournalEntry,
  deleteJournalEntry,
  type JournalEntryDto,
} from "@/lib/drive";

/**
 * GET /api/drive/journal?vault=open
 *
 * Journal privacy is per-entry rather than per-bucket, so this filters on the entry's own
 * isPrivate flag rather than on the vault bucket list. Filtering happens here, on the server,
 * for the same reason as everything else: a private entry hidden in the browser is still in
 * the response and in devtools on a work laptop.
 */
export async function GET(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
    const entries = await getJournalEntries(session.accessToken);
    if (vaultOpen) return NextResponse.json({ entries });

    const visible = entries.filter((e) => !e.isPrivate);
    return NextResponse.json({
      entries: visible,
      hiddenCount: entries.length - visible.length,
    });
  } catch (err) {
    console.error("GET /api/drive/journal error:", err);
    return NextResponse.json(
      { error: "Failed to fetch journal entries" },
      { status: 500 }
    );
  }
}

/**
 * POST /api/drive/journal — creates or updates one entry.
 *
 * A new entry gets its id from the clock. Android ids come from a Room autoincrement, so the
 * two sequences are disjoint in practice: Room ids are small integers and these are epoch
 * milliseconds, which will not collide for any lifetime this app has.
 */
export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const body = await req.json();
    const content: string = (body.content ?? "").trim();
    if (!content) {
      return NextResponse.json({ error: "content is required" }, { status: 400 });
    }

    const now = Date.now();
    const entry: JournalEntryDto = {
      id: typeof body.id === "number" && body.id > 0 ? body.id : now,
      content,
      prompt: typeof body.prompt === "string" ? body.prompt : "",
      isPrivate: body.isPrivate === true,
      // Preserve the original time on an edit; only a genuinely new entry is stamped now.
      createdAt:
        typeof body.createdAt === "number" && body.createdAt > 0
          ? body.createdAt
          : now,
    };

    await saveJournalEntry(session.accessToken, entry);
    return NextResponse.json({ entry });
  } catch (err) {
    console.error("POST /api/drive/journal error:", err);
    return NextResponse.json(
      { error: "Failed to save journal entry" },
      { status: 500 }
    );
  }
}

export async function DELETE(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const id = Number(req.nextUrl.searchParams.get("id"));
    if (!id || Number.isNaN(id)) {
      return NextResponse.json({ error: "id is required" }, { status: 400 });
    }
    await deleteJournalEntry(session.accessToken, id);
    return NextResponse.json({ success: true });
  } catch (err) {
    console.error("DELETE /api/drive/journal error:", err);
    return NextResponse.json(
      { error: "Failed to delete journal entry" },
      { status: 500 }
    );
  }
}
