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
  getVaultBucketNames,
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

/**
 * Facts the Android client writes to meta.json in each meeting folder. Absent for meetings
 * uploaded before meta.json existed, so every field must have a fallback.
 */
interface MeetingMeta {
  title?: string;
  recordedAt?: number;
  durationMs?: number;
  bucket?: string;
  status?: string;
  /** Set while the meeting is in the phone's Recently Deleted. Hidden here when present. */
  deletedAt?: number | null;
}

function parseMeeting(
  folderId: string,
  folderName: string,
  folderModifiedTime: string,
  summaryContent: string | null,
  transcriptContent: string | null,
  meta: MeetingMeta | null,
  actionsContent: string | null = null
): Meeting {
  // meta.json is authoritative — it is the phone's own recording timestamp. The folder name
  // is a decent second, and the folder's modifiedTime only a last resort, since it moves
  // whenever the meeting is edited.
  const tsFromName = parseTimestampFromFolderName(folderName);
  const recordedAt =
    meta?.recordedAt ?? tsFromName ?? new Date(folderModifiedTime).getTime();

  // Parse title from summary h1
  let title = meta?.title ?? folderName;
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

  // Action items come from actions.json, which the phone publishes from the same column its
  // own screen reads. The [ACTION:] scrape below is only a fallback for meetings created here
  // before that existed: the phone strips those markers before saving, so scraping a
  // phone-recorded summary always found nothing.
  let actionItems: ActionItem[] = [];
  if (actionsContent) {
    try {
      const parsed = JSON.parse(actionsContent);
      if (Array.isArray(parsed)) {
        actionItems = parsed
          .filter((a) => typeof a?.title === "string")
          .map((a) => ({ title: a.title, bucket: a.bucket ?? "" }));
      }
    } catch {
      // Malformed file: fall through to the scrape rather than losing the meeting.
    }
  }
  if (actionItems.length === 0) {
    const src = summaryContent ?? "";
    let match: RegExpExecArray | null;
    const re = new RegExp(ACTION_REGEX.source, "gi");
    while ((match = re.exec(src)) !== null) {
      actionItems.push({
        title: match[1].trim(),
        bucket: match[2].trim(),
      });
    }
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
    bucket: meta?.bucket ?? "",
    deletedAt: meta?.deletedAt ?? null,
    // Was hardcoded to 0, so every meeting on the web claimed no duration.
    durationMs: meta?.durationMs ?? 0,
    transcript: transcriptBody,
    summary: summaryBody,
    actionItems,
    status,
  };
}

/**
 * GET /api/drive/meetings?vault=open
 *
 * Meetings can carry a bucket, and until now the web app did not know that — so a meeting in
 * a vault bucket was listed like any other, transcript and all. Filtering happens server-side
 * so a locked vault means the transcript never leaves the server.
 */
export async function GET(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const token = session.accessToken;
    const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
    const secondBrainId = await getSecondBrainFolderId(token);
    const meetingsFolderId = await getMeetingsFolderId(token, secondBrainId);
    const folders = await listMeetingFolders(token, meetingsFolderId);

    const meetings = await Promise.all(
      folders.map(async (f) => {
        const [summaryContent, transcriptContent, metaContent, actionsContent] =
          await Promise.all([
            readFileFromFolder(token, f.id, "summary.md"),
            readFileFromFolder(token, f.id, "transcript.md"),
            readFileFromFolder(token, f.id, "meta.json"),
            readFileFromFolder(token, f.id, "actions.json"),
          ]);
        let meta: MeetingMeta | null = null;
        if (metaContent) {
          // A malformed meta.json must not lose the meeting — fall back to the files.
          try {
            meta = JSON.parse(metaContent) as MeetingMeta;
          } catch {
            meta = null;
          }
        }
        return parseMeeting(
          f.id,
          f.name,
          f.modifiedTime,
          summaryContent,
          transcriptContent,
          meta,
          actionsContent
        );
      })
    );

    // Drop meetings the phone has deleted. Their files stay on Drive for the 90-day Recently
    // Deleted window, so folder presence alone is not a reliable signal that a meeting still
    // exists — without this, anything deleted on the phone lingered here for three months.
    const live = meetings.filter((m) => !m.deletedAt);

    // Sort newest first
    live.sort((a, b) => b.recordedAt - a.recordedAt);

    if (vaultOpen) return NextResponse.json({ meetings: live });

    const vaultBuckets = await getVaultBucketNames(token);
    // An empty bucket means unsorted, never vault — meetings are only auto-sorted into
    // non-vault buckets, so an unsorted meeting has not been hidden by omission.
    const visible = live.filter(
      (m) => !m.bucket || !vaultBuckets.includes(m.bucket)
    );
    return NextResponse.json({
      meetings: visible,
      hiddenCount: live.length - visible.length,
    });
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
    if (title !== undefined || summary !== undefined) {
      // The markers are no longer embedded: the phone strips them on read and keeps action
      // items in their own file, so writing them here only put literal "[ACTION: …]" text into
      // the summary Carl reads.
      summaryMd = `# ${title ?? ""}\n\n${summary ?? ""}`;
    }
    await updateMeetingFiles(
      token,
      folderId,
      transcriptMd,
      summaryMd,
      actionItems !== undefined ? JSON.stringify(actionItems) : undefined,
      // Title lives in meta.json as well as in the summary heading, and the phone reads meta
      // first — so a rename here that skipped it showed the old name on the phone forever.
      // updatedAt is what lets the phone know this copy is newer than its own.
      title !== undefined
        ? { title, updatedAt: Date.now() }
        : { updatedAt: Date.now() }
    );
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

    const summaryMd = `# ${title}\n\n${summary}`;

    const secondBrainId = await getSecondBrainFolderId(token);
    const meetingsFolderId = await getMeetingsFolderId(token, secondBrainId);
    const folderId = await createMeetingFolder(token, meetingsFolderId, folderName, transcriptMd, summaryMd);

    // Written here rather than left for the phone, which never sees this meeting: without
    // meta.json it had no bucket — so it could never be vault-filtered — no duration, and a
    // recording time only inferrable from the folder name.
    await updateMeetingFiles(
      token,
      folderId,
      undefined,
      undefined,
      JSON.stringify(actionItems ?? []),
      {
        title,
        recordedAt: now,
        durationMs: 0,
        bucket: "",
        status: "DONE",
        deletedAt: null,
        updatedAt: now,
      }
    );

    // A meeting created here has no meta.json — the phone writes that on its own upload. Pass
    // what we know so the response carries the real recording time rather than re-deriving it.
    const meeting = parseMeeting(
      folderId,
      folderName,
      new Date(now).toISOString(),
      summaryMd,
      transcriptMd,
      { title, recordedAt: now, durationMs: 0, bucket: "", status: "DONE" }
    );

    return NextResponse.json({ meeting });
  } catch (err) {
    console.error("POST /api/drive/meetings error:", err);
    return NextResponse.json({ error: "Failed to create meeting" }, { status: 500 });
  }
}
