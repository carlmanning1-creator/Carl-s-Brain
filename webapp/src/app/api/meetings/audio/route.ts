import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getSecondBrainFolderId, getMeetingsFolderId } from "@/lib/drive";
import { google } from "googleapis";

function getDriveClient(accessToken: string) {
  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  return google.drive({ version: "v3", auth });
}

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
    const drive = getDriveClient(token);

    // Convert Blob to Buffer
    const arrayBuffer = await audioBlob.arrayBuffer();
    const buffer = Buffer.from(arrayBuffer);

    // Check if recording.webm already exists in the folder
    const existing = await drive.files.list({
      q: `name = 'recording.webm' and '${meetingId}' in parents and trashed = false`,
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

// Suppress unused import warning — getMeetingsFolderId/getSecondBrainFolderId are
// kept in the import for future use but not currently needed by this route.
void getSecondBrainFolderId;
void getMeetingsFolderId;
