import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { google } from "googleapis";
import { meetingFolderIsVisible } from "@/lib/driveGuards";
import { escapeDriveQueryValue } from "@/lib/driveQuery";

export async function GET(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const folderId = req.nextUrl.searchParams.get("folderId");
  if (!folderId) {
    return NextResponse.json({ error: "Missing folderId" }, { status: 400 });
  }

  // A folder id is a stable, shareable string, and this route used to serve any of them: a
  // vault-bucketed meeting's recording kept streaming from an id captured before the vault was
  // locked, and any folder in Carl's Drive could be named. The meetings list has always
  // filtered vault buckets; this now applies the same rule, and refuses anything outside
  // SecondBrain entirely.
  const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
  if (!(await meetingFolderIsVisible(session.accessToken, folderId, vaultOpen))) {
    return NextResponse.json({ error: "Not found" }, { status: 404 });
  }

  try {
    const auth = new google.auth.OAuth2();
    auth.setCredentials({ access_token: session.accessToken });
    const drive = google.drive({ version: "v3", auth });

    // Find recording file in the folder (m4a from phone or webm from browser)
    const list = await drive.files.list({
      q: `'${escapeDriveQueryValue(folderId)}' in parents and trashed = false and (name = 'recording.m4a' or name = 'recording.webm')`,
      fields: "files(id, name, mimeType)",
    });

    const file = list.data.files?.[0];
    if (!file?.id) {
      return NextResponse.json({ error: "No recording found" }, { status: 404 });
    }

    // Stream the file back to the browser
    const res = await drive.files.get(
      { fileId: file.id, alt: "media" },
      { responseType: "stream" }
    );

    const mimeType = file.name?.endsWith(".webm") ? "audio/webm" : "audio/mp4";
    const headers = new Headers({ "Content-Type": mimeType });

    const stream = res.data as NodeJS.ReadableStream;
    const webStream = new ReadableStream({
      start(controller) {
        stream.on("data", (chunk) => controller.enqueue(chunk));
        stream.on("end", () => controller.close());
        stream.on("error", (err) => controller.error(err));
      },
    });

    return new NextResponse(webStream, { headers });
  } catch (err) {
    console.error("GET /api/drive/meetings/audio error:", err);
    return NextResponse.json({ error: "Failed to fetch recording" }, { status: 500 });
  }
}
