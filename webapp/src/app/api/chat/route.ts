import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getMemory, getApiKey } from "@/lib/drive";
import { streamChatResponse, type ChatAttachment } from "@/lib/claude";
import type { ChatMessage } from "@/lib/types";

/** What Claude reads directly. Anything else has to become one of these first. */
const ALLOWED_ATTACHMENT_TYPES = [
  "application/pdf",
  "image/jpeg",
  "image/png",
  "image/gif",
  "image/webp",
];

/** 25 MB, expressed in base64 characters — base64 is 4 characters per 3 bytes. */
const MAX_ATTACHMENT_BASE64 = Math.ceil((25 * 1024 * 1024 * 4) / 3);

export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const body = await req.json();
    const messages: ChatMessage[] = body.messages;

    if (!messages || !Array.isArray(messages) || messages.length === 0) {
      return NextResponse.json(
        { error: "messages array is required" },
        { status: 400 }
      );
    }

    // Fetch API key and memory in parallel
    const [apiKey, memory] = await Promise.all([
      getApiKey(session.accessToken),
      getMemory(session.accessToken),
    ]);

    if (!apiKey) {
      return NextResponse.json(
        {
          error:
            "No Anthropic API key configured. Please add your API key in Settings.",
        },
        { status: 400 }
      );
    }

    // The model has no clock, and without a date it guesses — a "good morning" on a Monday
    // came back describing Sunday. Kept in step with PromptContext.rightNow() on the phone.
    // Australia/Sydney rather than the server's zone: this runs on Vercel, which is nowhere
    // near Dubbo, so UTC would be wrong by ten hours every time.
    const nowLocal = new Date().toLocaleString("en-AU", {
      timeZone: "Australia/Sydney",
      weekday: "long",
      day: "numeric",
      month: "long",
      year: "numeric",
      hour: "numeric",
      minute: "2-digit",
    });

    const systemPrompt = `${memory}

---
You are Carl's Brain — Carl's personal AI assistant and second brain. You have access to his memory context above.

## Right now
It is ${nowLocal} in Australia/Sydney.
Use this for anything relative — today, tomorrow, this week, overdue. Never guess the date or
the day of the week, and never infer the time of day from how Carl greets you.

## How to answer
Carl has ADHD. Length is a cost, not a courtesy.
- Lead with the answer. No preamble, no restating the question, no summary at the end.
- Two or three sentences is usually right. A short list is fine when the answer really is a
  list; prose padding around it is not.
- When he asks what to do, name ONE thing.
- Do not offer further help, and do not end with a question unless you actually need an answer
  to proceed.
- Go longer only if he asks for detail, or the subject genuinely cannot be said briefly.

Guidelines:
- Use Australian English spelling
- Proactively surface important information
- Help break down complex tasks into small steps
- Remember context from earlier in this conversation
- If asked to save something, acknowledge it clearly${
      body.unleashed === true
        ? `

You are in unleashed mode. Two kinds of tool are available.

Carl's own material — search_notes, search_todos, search_journal, get_calendar. Reach for these
before claiming something is or is not on his list, and before saying what his day looks like.
They are already filtered: anything in a vault bucket, and any private journal entry, simply
does not exist as far as these tools are concerned. Do not tell him something is missing on the
strength of an empty result.

The open web — search and fetch. Use it when the answer genuinely depends on something outside
Carl's own material: current facts, documentation, prices, news. Do not search for what you
already know, and never search for anything about Carl himself — his material is in the context
above and in the tools, and it is not on the internet. Cite what you used, briefly.`
        : ""
    }`;

    const unleashed = body.unleashed === true;

    // Attachments are validated here rather than trusted: the browser sends base64, and an
    // oversized or unsupported one should fail with a message Carl can act on rather than as
    // a 400 from the API. 25 MB is the API's own document ceiling.
    const rawAttachments: unknown = body.attachments;
    const attachments: ChatAttachment[] = Array.isArray(rawAttachments)
      ? rawAttachments
          .map((a) => a as Record<string, unknown>)
          .filter(
            (a) =>
              typeof a.data === "string" &&
              typeof a.mediaType === "string" &&
              ALLOWED_ATTACHMENT_TYPES.includes(a.mediaType)
          )
          .map((a) => ({
            name: typeof a.name === "string" ? a.name : "attachment",
            mediaType: a.mediaType as string,
            data: a.data as string,
          }))
      : [];

    const oversize = attachments.find((a) => a.data.length > MAX_ATTACHMENT_BASE64);
    if (oversize) {
      return NextResponse.json(
        { error: `${oversize.name} is too large — 25 MB is the limit.` },
        { status: 400 }
      );
    }

    const stream = await streamChatResponse(apiKey, systemPrompt, messages, {
      unleashed,
      attachments,
      // Only unleashed mode uses this — its tools read Carl's own Drive, server-side, so the
      // vault filtering happens before anything reaches the model.
      accessToken: session.accessToken,
    });

    return new Response(stream, {
      headers: {
        "Content-Type": "text/plain; charset=utf-8",
        "Transfer-Encoding": "chunked",
        "Cache-Control": "no-cache",
        "X-Content-Type-Options": "nosniff",
      },
    });
  } catch (err) {
    console.error("POST /api/chat error:", err);
    return NextResponse.json(
      { error: "Failed to generate response" },
      { status: 500 }
    );
  }
}
