import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { meetingFolderIsVisible } from "@/lib/driveGuards";
import { escapeDriveQueryValue } from "@/lib/driveQuery";
import { google } from "googleapis";

function getDriveClient(accessToken: string) {
  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  return google.drive({ version: "v3", auth });
}

/**
 * POST /api/meetings/audio — stores a browser recording in its meeting folder.
 *
 * `meetingId` is a Drive folder id supplied by the caller, and this app's token has full
 * `drive` scope. Unguarded, it wrote `recording.webm` into *any* folder in Carl's Drive and
 * could overwrite the recording inside a vault-bucketed meeting while the vault was locked —
 * and it interpolated the id into a Drive query without escaping, which is precisely what
 * `escapeDriveQueryValue` exists to prevent. Both are fixed below; the visibility check fails
 * closed.
 */
export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const formData = await req.formData();
    const audioBlob = formData.get("audio") as Blob | null;
    const meetingId = formData.get("meetingId") as string | null;

    if (!audioBlob || !meetingId) {
      return NextResponse.json({ error: "audio and meetingId are required" }, { status: 400 });
    }

    const token = session.accessToken;
    const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
    if (!(await meetingFolderIsVisible(token, meetingId, vaultOpen))) {
      // 404, not 403: the response must not reveal whether the folder exists.
      return NextResponse.json({ error: "Meeting not found" }, { status: 404 });
    }
    const drive = getDriveClient(token);

    // Convert Blob to Buffer
    const arrayBuffer = await audioBlob.arrayBuffer();
    const buffer = Buffer.from(arrayBuffer);

    // Check if recording.webm already exists in the folder
    const existing = await drive.files.list({
      q: `name = 'recording.webm' and '${escapeDriveQueryValue(meetingId)}' in parents and trashed = false`,
      fields: "files(id)",
    });

    const { Readable } = await import("stream");
    const readableStream = Readable.from(buffer);

    if (existing.data.files && existing.data.files.length > 0) {
      // Update existing file
      await drive.files.update({
        fileId: existing.data.files[0].id!,
        media: { mimeType: "audio/webm", body: readableStream },
      });
    } else {
      // Create new file
      await drive.files.create({
        requestBody: {
          name: "recording.webm",
          parents: [meetingId],
        },
        media: { mimeType: "audio/webm", body: readableStream },
        fields: "id",
      });
    }

    return NextResponse.json({ success: true });
  } catch (err) {
    console.error("POST /api/meetings/audio error:", err);
    return NextResponse.json({ error: "Failed to save audio" }, { status: 500 });
  }
}
