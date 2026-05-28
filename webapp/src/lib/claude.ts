import Anthropic from "@anthropic-ai/sdk";
import type { ChatMessage } from "./types";

export function createAnthropicClient(apiKey: string): Anthropic {
  return new Anthropic({ apiKey });
}

export async function streamChatResponse(
  apiKey: string,
  systemPrompt: string,
  messages: ChatMessage[]
): Promise<ReadableStream<Uint8Array>> {
  const client = createAnthropicClient(apiKey);

  const stream = await client.messages.stream({
    model: "claude-haiku-4-5",
    max_tokens: 2048,
    system: systemPrompt,
    messages: messages.map((m) => ({
      role: m.role,
      content: m.content,
    })),
  });

  const encoder = new TextEncoder();

  return new ReadableStream<Uint8Array>({
    async start(controller) {
      try {
        for await (const event of stream) {
          if (
            event.type === "content_block_delta" &&
            event.delta.type === "text_delta"
          ) {
            controller.enqueue(encoder.encode(event.delta.text));
          }
        }
        controller.close();
      } catch (err) {
        controller.error(err);
      }
    },
  });
}

export async function generateBriefing(
  apiKey: string,
  systemPrompt: string,
  todayEvents: string,
  urgentTodos: string
): Promise<string> {
  const client = createAnthropicClient(apiKey);

  const message = await client.messages.create({
    model: "claude-haiku-4-5",
    max_tokens: 512,
    system: systemPrompt,
    messages: [
      {
        role: "user",
        content: `Generate a concise morning briefing paragraph for today. Here's what's on:

Calendar events today:
${todayEvents || "No events scheduled today."}

Urgent/High priority todos:
${urgentTodos || "No urgent items."}

Write a natural, supportive 2-3 sentence briefing. Mention the most important items and offer a brief focus suggestion. Use Australian English.`,
      },
    ],
  });

  const block = message.content[0];
  return block.type === "text" ? block.text : "";
}
