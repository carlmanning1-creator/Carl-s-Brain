import { describe, it, expect } from "vitest";
import { memoryForPrompt, MEMORY_PROMPT_MAX_CHARS } from "./memoryPrompt";

describe("memoryForPrompt", () => {
  it("returns a short file untouched", () => {
    const memory = "- [2026-01-01] Carl trains on Sundays\n- [2026-01-02] Bec is out by 08:00";
    expect(memoryForPrompt(memory)).toBe(memory);
  });

  it("returns a file exactly at the cap untouched", () => {
    const memory = "x".repeat(MEMORY_PROMPT_MAX_CHARS);
    expect(memoryForPrompt(memory)).toBe(memory);
  });

  it("keeps the newest facts and drops the oldest", () => {
    const old = "- [2020-01-01] ancient fact\n";
    const filler = `${"- [2026-01-01] filler\n".repeat(1000)}`;
    const newest = "- [2026-09-07] Grace works at NSW SES";
    const out = memoryForPrompt(old + filler + newest);

    expect(out).toContain(newest);
    expect(out).not.toContain("ancient fact");
  });

  it("says so when it has dropped something, so Claude does not assert absence", () => {
    const out = memoryForPrompt("y".repeat(MEMORY_PROMPT_MAX_CHARS + 1));
    expect(out).toContain("Older entries");
    expect(out).toContain("say so rather than assuming");
  });

  it("never starts mid-fact", () => {
    // Every line is the same length, so the cut lands inside one of them; the result must
    // still begin at a line boundary rather than half-way through a sentence.
    const line = "- [2026-01-01] a fact that is reasonably long\n";
    const out = memoryForPrompt(line.repeat(500));
    const body = out.split("\n\n").slice(1).join("\n\n");
    expect(body.startsWith("- [")).toBe(true);
  });

  it("handles an empty file", () => {
    expect(memoryForPrompt("")).toBe("");
  });
});
