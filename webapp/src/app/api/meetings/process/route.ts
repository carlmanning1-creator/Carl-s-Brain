import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getApiKey, getNonVaultBucketNames } from "@/lib/drive";
import { createAnthropicClient } from "@/lib/claude";
import { ACTION_REGEX } from "@/lib/fileFormat";
import type { ActionItem } from "@/lib/types";

export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const { transcript } = (await req.json()) as { transcript: string };

    if (!transcript || !transcript.trim()) {
      return NextResponse.json({ error: "transcript is required" }, { status: 400 });
    }

    const apiKey = await getApiKey(session.accessToken);
    if (!apiKey) {
      return NextResponse.json({ error: "No Anthropic API key configured. Set one in Settings." }, { status: 400 });
    }

    const client = createAnthropicClient(apiKey);

    // The real bucket list, non-vault only — the same rule the phone's analysis prompt follows.
    //
    // These five names were hardcoded, which is the defect getBucketConfig was written to
    // remove: the prompt could not offer a bucket Carl had created, and could propose one he
    // had since marked vault — filing an action item straight into a bucket the app then hides.
    const bucketNames = (await getNonVaultBucketNames(session.accessToken)).join(", ")
      || "SES, Family, Work, Personal, Other";

    const prompt = `Analyse this meeting transcript. Produce exactly this format — do not deviate:

TITLE: [brief descriptive title, max 8 words]

SUMMARY:
[3-5 sentence summary of key points]

ACTION ITEMS:
[ACTION: task description | bucket]
Example: [ACTION: Call John about insurance renewal | Work]
IMPORTANT: Every action item MUST use exactly this format with square brackets, ACTION: prefix, and pipe separator.

Buckets must be one of: ${bucketNames}

TRANSCRIPT:
${transcript}`;

    const message = await client.messages.create({
      model: "claude-haiku-4-5",
      max_tokens: 1024,
      messages: [{ role: "user", content: prompt }],
    });

    const responseText = message.content[0].type === "text" ? message.content[0].text : "";

    // Parse TITLE
    const titleMatch = responseText.match(/^TITLE:\s*(.+)/m);
    const title = titleMatch ? titleMatch[1].trim() : "Untitled Meeting";

    // Parse SUMMARY (between SUMMARY: and ACTION ITEMS: or end)
    const summaryMatch = responseText.match(/SUMMARY:\s*\n([\s\S]*?)(?=\nACTION ITEMS:|$)/);
    const summary = summaryMatch ? summaryMatch[1].trim() : "";

    // Parse ACTION ITEMS
    const actionItems: ActionItem[] = [];
    // The shared regex, not a second one written here.
    //
    // This file had its own, with an optional closing bracket and a lazy bucket group, so it
    // matched the shortest bucket that let the match succeed: every action item from a
    // web-processed meeting was filed under a bucket called "W", leaving "ork]" in the summary.
    // matchAll rather than an exec loop, so the shared regex's lastIndex cannot leak between
    // requests.
    for (const match of responseText.matchAll(ACTION_REGEX)) {
      actionItems.push({
        title: match[1].trim(),
        bucket: match[2].trim(),
      });
    }

    return NextResponse.json({ title, summary, actionItems });
  } catch (err) {
    console.error("POST /api/meetings/process error:", err);
    return NextResponse.json({ error: "Failed to process transcript" }, { status: 500 });
  }
}
