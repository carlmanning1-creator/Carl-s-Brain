import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getJournalTemplates, getVaultBucketNames } from "@/lib/drive";

/**
 * GET /api/drive/journal-templates?vault=open
 *
 * Read-only. Carl builds and edits templates on the phone — that is where he journals — and
 * this exists so the web app can say *which* template an entry was written against and show
 * what it asks, rather than presenting a wall of rendered text with no structure.
 *
 * A template whose default bucket is a vault bucket is withheld while the vault is locked: the
 * name alone ("Kink") is the kind of thing the vault exists to keep off a work laptop.
 * Templates flagged private-by-default are withheld too, for the same reason.
 */
export async function GET(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
    const templates = await getJournalTemplates(session.accessToken);
    if (vaultOpen) return NextResponse.json({ templates });

    const vaultBuckets = await getVaultBucketNames(session.accessToken);
    const visible = templates.filter(
      (t) =>
        !t.isPrivateByDefault &&
        !(
          t.bucketName &&
          vaultBuckets.some((b) => b.toLowerCase() === t.bucketName.toLowerCase())
        )
    );
    return NextResponse.json({
      templates: visible,
      hiddenCount: templates.length - visible.length,
    });
  } catch (err) {
    console.error("GET /api/drive/journal-templates error:", err);
    return NextResponse.json(
      { error: "Failed to fetch journal templates" },
      { status: 500 }
    );
  }
}
