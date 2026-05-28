import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { google } from "googleapis";

function getDriveClient(accessToken: string) {
  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  return google.drive({ version: "v3", auth });
}

/**
 * POST /api/drive/share
 * Body: { fileId: string }
 *
 * Makes the Drive file publicly readable (anyone with the link) and returns its webViewLink.
 * For note files pass the Drive file ID directly.
 * For meeting summary.md pass the summary file's ID (use GET with folderId to look it up first).
 */
export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  let fileId: string | undefined;
  try {
    const body = await req.json();
    fileId = body.fileId;
  } catch {
    return NextResponse.json({ error: "Invalid request body" }, { status: 400 });
  }

  if (!fileId) {
    return NextResponse.json({ error: "fileId is required" }, { status: 400 });
  }

  try {
    const drive = getDriveClient(session.accessToken);

    // Create an "anyone with the link can view" permission
    await drive.permissions.create({
      fileId,
      requestBody: {
        type: "anyone",
        role: "reader",
      },
    });

    // Fetch the webViewLink
    const fileRes = await drive.files.get({
      fileId,
      fields: "webViewLink",
    });

    const webViewLink = fileRes.data.webViewLink;
    if (!webViewLink) {
      return NextResponse.json({ error: "Could not retrieve webViewLink" }, { status: 500 });
    }

    return NextResponse.json({ webViewLink });
  } catch (err) {
    console.error("POST /api/drive/share error:", err);
    return NextResponse.json({ error: "Failed to share file" }, { status: 500 });
  }
}

/**
 * GET /api/drive/share?folderId=<id>
 *
 * Looks up summary.md inside the given meeting folder and returns its Drive file ID.
 * Used by the meeting share flow to resolve the summary file before calling POST.
 */
export async function GET(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const { searchParams } = new URL(req.url);
  const folderId = searchParams.get("folderId");
  if (!folderId) {
    return NextResponse.json({ error: "folderId is required" }, { status: 400 });
  }

  try {
    const drive = getDriveClient(session.accessToken);

    const res = await drive.files.list({
      q: `name = 'summary.md' and '${folderId}' in parents and trashed = false`,
      fields: "files(id, name)",
      spaces: "drive",
    });

    if (!res.data.files || res.data.files.length === 0) {
      return NextResponse.json({ error: "summary.md not found in meeting folder" }, { status: 404 });
    }

    return NextResponse.json({ fileId: res.data.files[0].id });
  } catch (err) {
    console.error("GET /api/drive/share error:", err);
    return NextResponse.json({ error: "Failed to look up summary file" }, { status: 500 });
  }
}
