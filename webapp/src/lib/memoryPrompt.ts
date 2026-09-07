/**
 * How much of `memory.md` goes into a Claude prompt.
 *
 * The counterpart to `domain/MemoryPrompt.kt` on the phone, and deliberately identical: the
 * same conversation moves between the two, and a question answered from more context on one
 * device than the other is the sort of difference that reads as the app being unreliable.
 *
 * `memory.md` only ever grows — the phone appends to it after captures, notes, calendar events
 * and voice conversations, and nothing removes a line, by design. But the whole file is
 * prepended to every Claude call, so its length is a tax on every reply, forever.
 *
 * This trims what is *sent*, never the file. The file stays complete, editable in Settings and
 * exported whole; raising the cap restores everything immediately. The tail is kept because
 * appended facts are the newest, the cut lands on a line boundary so no fact is half-quoted,
 * and a note says when anything was dropped — a model told its context is partial can say "I
 * don't have that" instead of asserting the commitment does not exist.
 */

/** Characters kept. Matches MemoryPrompt.MAX_CHARS on the phone; keep the two in step. */
export const MEMORY_PROMPT_MAX_CHARS = 8000;

const TRUNCATION_NOTE =
  "[Older entries in Carl's memory file are not included here. " +
  "If something seems missing, say so rather than assuming it does not exist.]";

export function memoryForPrompt(memory: string): string {
  if (memory.length <= MEMORY_PROMPT_MAX_CHARS) return memory;
  const tail = memory.slice(-MEMORY_PROMPT_MAX_CHARS);
  const newline = tail.indexOf("\n");
  const whole = newline === -1 ? tail : tail.slice(newline + 1);
  return `${TRUNCATION_NOTE}\n\n${whole}`;
}
