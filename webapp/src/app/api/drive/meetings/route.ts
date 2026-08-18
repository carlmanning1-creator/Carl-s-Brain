import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import {
  getSecondBrainFolderId,
  getMeetingsFolderId,
  listMeetingFolders,
  readFileFromFolder,
  createMeetingFolder,
  updateMeetingFiles,
} from "@/lib/drive";
import type { Meeting, ActionItem } from "@/lib/types";

const ACTION_REGEX = /\[ACTION:\s*([^\]|]+)\|\s*([^\]]+)\]/gi;

/**
 * Reads the recording time out of a meeting folder name.
 *
 * The Android client names folders "yyyy-MM-dd HH-mm optional title", e.g.
 * "2026-08-18 14-30 Team standup" (MeetingUploadWorker.FOLDER_DATE_FORMAT).
 *
 * The previous pattern required a "T" or "_" between the date and the time, so it never
 * matched a real folder and every meeting silently fell back to the folder's modifiedTime.
 * That is close enough to look right and wrong enough to misorder the list, and it shifts
 * whenever a meeting is edited. Treat the folder name as a wire format shared with Android.
 */
function parseTimestampFromFolderName(name: string): number | null {
  // Pure epoch-ms prefix, kept for any folder created by an older build.
  const epochMatch = name.match(/^(\d{13,})/);
  if (epochMatch) return parseInt(epochMatch[1], 10);

  // Date, then space / T / underscore, then HH-mm or HH:mm.
  const m = name.match(/(\d{4})-(\d{2})-(\d{2})[ T_](\d{2})[:-](\d{2})/);
  if (m) {
    const [, y, mo, d, h, min] = m;
    // Constructed in local time, matching the device that recorded it — the Android side
    // formats with the device clock, so parsing as UTC would shift every meeting.
    const parsed = new Date(
      Number(y),
      Number(mo) - 1,
      Number(d),
      Number(h),
      Number(min)
    ).getTime();
    if (!isNaN(parsed)) return parsed;
  }

  // Date only, no time — still better than the folder's modifiedTime.
  const dateOnly = name.match(/(\d{4})-(\d{2})-(\d{2})/);
  if (dateOnly) {
    const [, y, mo, d] = dateOnly;
    const parsed = new Date(Number(y), Number(mo) - 1, Number(d)).getTime();
    if (!isNaN(parsed)) return parsed;
  }

  return null;
}

function parseMeeting(
  folderId: string,
  folderName: string,
  folderModifiedTime: string,
  summaryContent: string | null,
  transcriptContent: string | null
): Meeting {
  // Parse recordedAt
  const tsFromName = parseTimestampFromFolderName(folderName);
  const recordedAt = tsFromName ?? new Date(folderModifiedTime).getTime();

  // Parse title from summary h1
  let title = folderName;
  let summaryBody = "";
  if (summaryContent) {
    const lines = summaryContent.split("\n");
    if (lines[0].startsWith("# ")) {
      title = lines[0].slice(2).trim();
      summaryBody = lines.slice(1).join("\n").trim();
    } else {
      summaryBody = summaryContent.trim();
    }
  }

  // Parse transcript body (strip leading # heading)
  let transcriptBody = "";
  if (transcriptContent) {
    const lines = transcriptContent.split("\n");
    if (lines[0].startsWith("#")) {
      transcriptBody = lines.slice(1).join("\n").trim();
    } else {
      transcriptBody = transcriptContent.trim();
    }
  }

  // Parse action items from summary
  const actionItems: ActionItem[] = [];
  const src = summaryContent ?? "";
  let match: RegExpExecArray | null;
  const re = new RegExp(ACTION_REGEX.source, "gi");
  while ((match = re.exec(src)) !== null) {
    actionItems.push({
      title: match[1].trim(),
      bucket: match[2].trim(),
    });
  }

  // Determine status
  let status: Meeting["status"] = "DONE";
  if (!transcriptContent && !summaryContent) {
    status = "AUDIO_ONLY";
  } else if (!transcriptContent) {
    status = "NO_TRANSCRIPT";
  }

  return {
    id: folderId,
    folderName,
    title,
    recordedAt,
    durationMs: 0,
    transcript: transcriptBody,
    summary: summaryBody,
    actionItems,
    status,
  };
}

export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const token = session.accessToken;
    const secondBrainId = await getSecondBrainFolderId(token);
    const meetingsFolderId = await getMeetingsFolderId(token, secondBrainId);
    const folders = await listMeetingFolders(token, meetingsFolderId);

    const meetings = await Promise.all(
      folders.map(async (f) => {
        const [summaryContent, transcriptContent] = await Promise.all([
          readFileFromFolder(token, f.id, "summary.md"),
          readFileFromFolder(token, f.id, "transcript.md"),
        ]);
        return parseMeeting(f.id, f.name, f.modifiedTime, summaryContent, transcriptContent);
      })
    );

    // Sort newest first
    meetings.sort((a, b) => b.recordedAt - a.recordedAt);

    return NextResponse.json({ meetings });
  } catch (err) {
    console.error("GET /api/drive/meetings error:", err);
    return NextResponse.json({ error: "Failed to fetch meetings" }, { status: 500 });
  }
}

export async function PATCH(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }
  try {
    const { folderId, transcript, title, summary, actionItems } = await req.json() as {
      folderId: string;
      transcript?: string;
      title?: string;
      summary?: string;
      actionItems?: ActionItem[];
    };
    const token = session.accessToken;
    const transcriptMd = transcript !== undefined ? `# Transcript\n\n${transcript}` : undefined;
    let summaryMd: string | undefined;
    if (title !== undefined || summary !== undefined || actionItems !== undefined) {
      const actionLines = (actionItems ?? []).map((a) => `[ACTION: ${a.title} | ${a.bucket}]`).join("\n");
      summaryMd = `# ${title ?? ""}\n\n${summary ?? ""}${actionItems?.length ? "\n\n" + actionLines : ""}`;
    }
    await updateMeetingFiles(token, folderId, transcriptMd, summaryMd);
    return NextResponse.json({ ok: true });
  } catch (err) {
    console.error("PATCH /api/drive/meetings error:", err);
    return NextResponse.json({ error: "Failed to update meeting" }, { status: 500 });
  }
}

export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const body = await req.json();
    const { title, transcript, summary, actionItems } = body as {
      title: string;
      transcript: string;
      summary: string;
      actionItems: ActionItem[];
    };

    const token = session.accessToken;
    const now = Date.now();
    const folderName = `${now}`;

    const transcriptMd = `# Transcript\n\n${transcript}`;

    // Build summary.md with action items embedded
    const actionLines = actionItems
      .map((a) => `[ACTION: ${a.title} | ${a.bucket}]`)
      .join("\n");
    const summaryMd = `# ${title}\n\n${summary}${actionItems.length > 0 ? "\n\n" + actionLines : ""}`;

    const secondBrainId = await getSecondBrainFolderId(token);
    const meetingsFolderId = await getMeetingsFolderId(token, secondBrainId);
    const folderId = await createMeetingFolder(token, meetingsFolderId, folderName, transcriptMd, summaryMd);

    const meeting = parseMeeting(folderId, folderName, new Date(now).toISOString(), summaryMd, transcriptMd);

    return NextResponse.json({ meeting });
  } catch (err) {
    console.error("POST /api/drive/meetings error:", err);
    return NextResponse.json({ error: "Failed to create meeting" }, { status: 500 });
  }
}
