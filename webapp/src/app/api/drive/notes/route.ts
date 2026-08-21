import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import {
  getNotes,
  saveNote,
  deleteNote,
  getVaultBucketNames,
} from "@/lib/drive";
import { validEntityId } from "@/lib/driveQuery";

/**
 * GET /api/drive/notes?vault=open
 *
 * Vault filtering is done here rather than in the browser, for the same reason as todos: a
 * note hidden client-side is still in the response and in devtools on a work laptop. Notes
 * carry more free text than to-dos, so this is the one where leaking the body matters most.
 */
export async function GET(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
    const notes = await getNotes(session.accessToken);
    if (vaultOpen) return NextResponse.json({ notes });

    const vaultBuckets = await getVaultBucketNames(session.accessToken);
    // A note whose file carries no bucket comment is withheld rather than shown: we cannot tell
    // whether it belongs to a vault bucket, and guessing wrong is a leak. The phone republishes
    // every note with its bucket on the first sync after this change, so this self-heals.
    const visible = notes.filter(
      (n) =>
        !!n.bucket &&
        !vaultBuckets.some((b) => b.toLowerCase() === n.bucket.toLowerCase())
    );
    return NextResponse.json({
      notes: visible,
      hiddenCount: notes.length - visible.length,
    });
  } catch (err) {
    console.error("GET /api/drive/notes error:", err);
    return NextResponse.json(
      { error: "Failed to fetch notes" },
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
    const { id, title, content, bucket } = body;

    if (!id || !title) {
      return NextResponse.json(
        { error: "id and title are required" },
        { status: 400 }
      );
    }

    // The id becomes part of a filename AND part of a Drive query, so an arbitrary string here
    // could match a file this route was never asked about — memory.md, say, which would then be
    // overwritten with note content. Every id either client produces is an integer.
    const safeId = validEntityId(String(id));
    if (!safeId) {
      return NextResponse.json({ error: "Invalid id" }, { status: 400 });
    }

    await saveNote(
      session.accessToken,
      safeId,
      title,
      content ?? "",
      bucket ?? "Personal"
    );

    return NextResponse.json({ success: true, id });
  } catch (err) {
    console.error("POST /api/drive/notes error:", err);
    return NextResponse.json(
      { error: "Failed to save note" },
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
    const { searchParams } = new URL(req.url);
    const id = searchParams.get("id");

    if (!id) {
      return NextResponse.json({ error: "id is required" }, { status: 400 });
    }

    // Same reasoning as POST — and here the consequence is a trashed file, not an overwritten
    // one, so the check matters more rather than less.
    const safeId = validEntityId(id);
    if (!safeId) {
      return NextResponse.json({ error: "Invalid id" }, { status: 400 });
    }

    await deleteNote(session.accessToken, safeId);
    return NextResponse.json({ success: true });
  } catch (err) {
    console.error("DELETE /api/drive/notes error:", err);
    return NextResponse.json(
      { error: "Failed to delete note" },
      { status: 500 }
    );
  }
}
