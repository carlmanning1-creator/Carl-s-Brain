import Anthropic from "@anthropic-ai/sdk";
import type { ChatMessage } from "./types";

export function createAnthropicClient(apiKey: string): Anthropic {
  return new Anthropic({ apiKey });
}

/**
 * The two chat modes.
 *
 * Default is Carl's second brain talking about Carl's own material: Sonnet 5, no tools, the
 * behaviour he already had. Unleashed is the same conversation with the ceiling raised and the
 * open web available — Opus 5 plus the server-side search and fetch tools, which Anthropic runs
 * so there is no agent loop on our side.
 *
 * Deliberately per-conversation rather than a stored setting: it is a mode Carl flips for a
 * question, not a preference he sets once. It also costs real money per search, which is a
 * reason for it to be a visible switch rather than something quietly always on.
 */
export interface ChatModeOptions {
  unleashed?: boolean;
  /**
   * Files attached to the most recent user message — PDFs and images Claude reads directly.
   *
   * Deliberately not part of the saved conversation. `chat_<id>.md` is markdown shared with
   * the phone, and there is nowhere in it for a 4 MB PDF; the transcript records that a file
   * was attached and what it was called, which is what makes the exchange readable later. If
   * a document is worth keeping it belongs in Drive as an attachment on a note, where both
   * clients can already find it.
   */
  attachments?: ChatAttachment[];
}

export interface ChatAttachment {
  name: string;
  /** "application/pdf", "image/png", … */
  mediaType: string;
  /** base64, without a data: prefix. */
  data: string;
}

/**
 * Turns the transcript into API messages, attaching any files to the final user turn.
 *
 * Attachments go on the last message rather than being replayed on every turn: they are what
 * Carl just handed over, and re-sending a PDF with each follow-up would multiply the cost of
 * the conversation by the size of the document.
 */
function buildMessages(messages: ChatMessage[], attachments: ChatAttachment[]) {
  const lastUser = messages.map((m) => m.role).lastIndexOf("user");
  return messages.map((m, i) => {
    if (i !== lastUser || attachments.length === 0) {
      return { role: m.role, content: m.content };
    }
    return {
      role: m.role,
      content: [
        ...attachments.map((a) =>
          a.mediaType === "application/pdf"
            ? ({
                type: "document" as const,
                source: {
                  type: "base64" as const,
                  media_type: "application/pdf" as const,
                  data: a.data,
                },
                title: a.name,
              })
            : ({
                type: "image" as const,
                source: {
                  type: "base64" as const,
                  media_type: a.mediaType as
                    | "image/jpeg"
                    | "image/png"
                    | "image/gif"
                    | "image/webp",
                  data: a.data,
                },
              })
        ),
        { type: "text" as const, text: m.content },
      ],
    };
  });
}

export async function streamChatResponse(
  apiKey: string,
  systemPrompt: string,
  messages: ChatMessage[],
  { unleashed = false, attachments = [] }: ChatModeOptions = {}
): Promise<ReadableStream<Uint8Array>> {
  const client = createAnthropicClient(apiKey);

  const stream = await client.messages.stream({
    // Default matches the phone's Chat: the one surface Carl thinks with rather than captures
    // into. Every background call in this app stays on Haiku.
    model: unleashed ? "claude-opus-5" : "claude-sonnet-5",
    max_tokens: unleashed ? 16000 : 8192,
    // Claude decides for itself when a question is worth thinking about. The stream below
    // forwards only text deltas, so the reasoning never reaches the page.
    thinking: { type: "adaptive" },
    // Server-side tools: Anthropic runs the search and the fetch, and only the answer comes
    // back, so there is no tool loop to write here. The _20260209 versions filter results
    // before they reach the context window.
    //
    // The standalone code_execution tool is deliberately NOT included alongside them — it
    // creates a second execution environment that competes with the one dynamic filtering
    // already uses, and confuses the model about which to reach for.
    ...(unleashed
      ? {
          tools: [
            { type: "web_search_20260209" as const, name: "web_search" as const },
            { type: "web_fetch_20260209" as const, name: "web_fetch" as const },
          ],
        }
      : {}),
    system: systemPrompt,
    messages: buildMessages(messages, attachments),
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
