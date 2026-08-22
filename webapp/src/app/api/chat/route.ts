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

    const systemPrompt = `${memory}

---
You are Carl's Brain — Carl's personal AI assistant and second brain. You have access to his memory context above.

Guidelines:
- Be concise and direct — Carl has ADHD
- Use Australian English spelling
- Proactively surface important information
- Help break down complex tasks into small steps
- Remember context from earlier in this conversation
- If asked to save something, acknowledge it clearly${
      body.unleashed === true
        ? `

You are in unleashed mode: you can search and fetch the open web. Use it when the answer
genuinely depends on something outside Carl's own material — current facts, documentation,
prices, news. Do not search for things you already know, and do not search for anything about
Carl himself: his material is in the context above, and it is not on the web.
Cite what you used, briefly.`
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
