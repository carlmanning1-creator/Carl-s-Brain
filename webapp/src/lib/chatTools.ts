import {
  getNotes,
  getTodos,
  getJournalEntries,
  getVaultBucketNames,
} from "./drive";
import { getUpcomingEvents } from "./calendar";

/**
 * The tools unleashed Chat can call against Carl's own material, web side.
 *
 * The counterpart to ChatTools.kt on the phone, and deliberately the same four tools with the
 * same names and the same shapes — a conversation that moves between the two devices should
 * not find different capabilities on either side of it.
 *
 * ## Read-only, by design
 *
 * Every tool here reads. Chat's writes still go through the `[TODO:]` / `[NOTE:]` markers on
 * the phone, which route through the same use cases the rest of the app does. A second,
 * parallel write path would have meant two places that can create a to-do and only one of
 * them correct.
 *
 * ## The vault rule
 *
 * Chat is a vault-closed surface, unconditionally — not "unless the vault happens to be open".
 * The filtering happens here, on the server, before anything reaches the model: an item
 * withheld in the browser has already been sent, and a prompt built from unfiltered data has
 * already left the building.
 */

export interface ChatToolDefinition {
  name: string;
  description: string;
  input_schema: {
    type: "object";
    properties: Record<string, { type: string; description: string }>;
  };
}

/** How many tool round trips one question may take before the loop gives up. */
export const MAX_TOOL_ITERATIONS = 6;

export const TOOL_DEFINITIONS: ChatToolDefinition[] = [
  {
    name: "search_notes",
    description:
      "Search Carl's notes by keyword, matching title and body. Returns the 20 most recent " +
      "matches, each truncated. Use when he refers to something he wrote down and you do not " +
      "already have it in context. Do not use it to answer a question you can already answer.",
    input_schema: {
      type: "object",
      properties: {
        query: {
          type: "string",
          description: "Keyword or phrase. A single distinctive word works best.",
        },
      },
    },
  },
  {
    name: "search_todos",
    description:
      "Search Carl's to-dos by title. Returns each match with its bucket, priority, due date " +
      "and whether it is done, unfinished ones first. Pass an empty query to list his current " +
      "to-dos. Use before claiming something is or is not on his list.",
    input_schema: {
      type: "object",
      properties: {
        query: {
          type: "string",
          description: "Keyword, or empty for his current to-dos.",
        },
      },
    },
  },
  {
    name: "search_journal",
    description:
      "Search Carl's journal entries by keyword. Private entries and entries in vault buckets " +
      "are never returned. Use for spotting patterns over time. Do not quote entries back at " +
      "him verbatim.",
    input_schema: {
      type: "object",
      properties: {
        query: { type: "string", description: "Keyword or phrase." },
      },
    },
  },
  {
    name: "get_calendar",
    description:
      "Carl's upcoming calendar events. Use before suggesting a time for anything, and before " +
      "saying what his day looks like.",
    input_schema: {
      type: "object",
      properties: {
        days_ahead: {
          type: "integer",
          description: "How many days forward to look. Default 7, max 60.",
        },
      },
    },
  },
];

/** Everything unleashed Chat is offered: Carl's own material plus the open web. */
export function unleashedTools() {
  return [
    ...TOOL_DEFINITIONS,
    { type: "web_search_20260209" as const, name: "web_search" as const },
    { type: "web_fetch_20260209" as const, name: "web_fetch" as const },
  ];
}

function formatDay(ms: number): string {
  if (!ms || Number.isNaN(ms)) return "unknown date";
  return new Date(ms).toLocaleString("en-AU", {
    weekday: "short",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/**
 * Runs one tool call and returns what Claude should see.
 *
 * Never throws: a failure comes back as text saying so, because the alternative is the whole
 * conversation failing over a Drive hiccup in a tool Claude reached for speculatively. It can
 * then say it could not look, which is honest and recoverable.
 */
export async function executeTool(
  accessToken: string,
  name: string,
  input: Record<string, unknown>
): Promise<string> {
  const query = typeof input.query === "string" ? input.query.trim() : "";
  try {
    switch (name) {
      case "search_notes": {
        if (!query) return "No query given.";
        const [notes, vault] = await Promise.all([
          getNotes(accessToken),
          getVaultBucketNames(accessToken),
        ]);
        const needle = query.toLowerCase();
        const hits = notes
          // Unknown bucket is withheld too, exactly as the notes route does: an untitled note
          // written before the bucket comment was unconditional carries none, and treating
          // unknown as public is how a vault note becomes a visible one.
          .filter(
            (n) =>
              n.bucket &&
              !vault.some((b) => b.toLowerCase() === n.bucket.toLowerCase())
          )
          .filter(
            (n) =>
              n.title.toLowerCase().includes(needle) ||
              n.content.toLowerCase().includes(needle)
          )
          .slice(0, 20);
        if (hits.length === 0) return `No notes match "${query}".`;
        return hits
          .map(
            (n) =>
              `• ${n.title || "(untitled)"} — ${n.content
                .slice(0, 300)
                .replace(/\n/g, " ")}`
          )
          .join("\n\n");
      }

      case "search_todos": {
        const [todos, vault] = await Promise.all([
          getTodos(accessToken),
          getVaultBucketNames(accessToken),
        ]);
        const needle = query.toLowerCase();
        const hits = todos
          .filter((t) => t.deletedAt == null)
          .filter(
            (t) => !vault.some((b) => b.toLowerCase() === (t.bucket ?? "").toLowerCase())
          )
          .filter((t) => !needle || t.title.toLowerCase().includes(needle))
          .sort((a, b) => Number(a.isDone) - Number(b.isDone))
          .slice(0, 30);
        if (hits.length === 0) {
          return query ? `No to-dos match "${query}".` : "No to-dos.";
        }
        return hits
          .map((t) => {
            const due = t.dueDate ? ` · due ${formatDay(t.dueDate)}` : "";
            return `• ${t.title} · ${t.bucket} · ${t.priority}${due}${
              t.isDone ? " · DONE" : ""
            }`;
          })
          .join("\n");
      }

      case "search_journal": {
        if (!query) return "No query given.";
        const [entries, vault] = await Promise.all([
          getJournalEntries(accessToken),
          getVaultBucketNames(accessToken),
        ]);
        const needle = query.toLowerCase();
        // Private OR vault-bucketed — the union, matching the journal route and the phone.
        // Filtering on isPrivate alone was a real leak: on the phone the bucket is what hides
        // a vault-bucketed entry, so ticking Private as well is the exception.
        const hits = entries
          .filter(
            (e) =>
              !e.isPrivate &&
              !(
                e.bucket &&
                vault.some((b) => b.toLowerCase() === e.bucket!.trim().toLowerCase())
              )
          )
          .filter((e) => e.content.toLowerCase().includes(needle))
          .slice(0, 15);
        if (hits.length === 0) return `No journal entries match "${query}".`;
        return hits
          .map(
            (e) =>
              `• ${formatDay(e.createdAt)} — ${e.content.slice(0, 300).replace(/\n/g, " ")}`
          )
          .join("\n\n");
      }

      case "get_calendar": {
        const raw = Number(input.days_ahead);
        const days = Number.isFinite(raw) ? Math.min(Math.max(raw, 1), 60) : 7;
        const events = await getUpcomingEvents(accessToken, days);
        if (events.length === 0) {
          return `Nothing in the calendar for the next ${days} days.`;
        }
        return events
          .slice(0, 40)
          .map((e) => `• ${formatDay(new Date(e.start).getTime())} — ${e.title}`)
          .join("\n");
      }

      default:
        return `Unknown tool: ${name}`;
    }
  } catch (err) {
    return `That lookup failed: ${
      err instanceof Error ? err.message : "unknown error"
    }`;
  }
}
