import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getMemory, getApiKey } from "@/lib/drive";
import { streamChatResponse } from "@/lib/claude";
import type { ChatMessage } from "@/lib/types";

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
- If asked to save something, acknowledge it clearly`;

    const stream = await streamChatResponse(apiKey, systemPrompt, messages);

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
