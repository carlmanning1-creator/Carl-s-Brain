import { describe, expect, it } from "vitest";
import {
  parseJournalFile,
  serialiseJournalFile,
  parseNoteFile,
  serialiseNoteFile,
  stampDeleted,
  parseChatFile,
  serialiseChatFile,
  type ChatThreadDto,
} from "./fileFormat";

/**
 * The Drive file formats are a contract between two clients that never talk directly.
 *
 * Every format bug found in the August 2026 review was the same shape: one side rewrote a file
 * and silently dropped a comment the other side had written. Nothing failed, nothing logged; the
 * loss only became visible on a device restore, by which time the original was gone.
 *
 * So the fixtures below are written the way the *Android* client writes them, and the central
 * assertion is always the same: parse it, serialise it back, and nothing the web app does not
 * understand may go missing.
 */

/** A journal entry exactly as DriveRepository.uploadJournalEntry writes it. */
const ANDROID_JOURNAL = [
  "<!-- private: false -->",
  "<!-- createdAt: 1755600000000 -->",
  "<!-- prompt: What did today take out of you? -->",
  "<!-- attachments: 1AbC,file:squat.mp4:2XyZ -->",
  "<!-- answers: {\"fields\":[],\"answers\":{}} -->",
  "<!-- mood: flat -->",
  "<!-- bucket: Kink -->",
  "<!-- updatedAt: 1755600001000 -->",
  "",
  "Wrote this on the phone.",
].join("\n");

/** A note as uploadNoteFile writes it, with a title. */
const ANDROID_NOTE = [
  "# Rope inventory",
  "<!-- bucket: Kink -->",
  "<!-- attachments: 9ZzZ -->",
  "<!-- updatedAt: 1755600001000 -->",
  "",
  "- [x] jute",
  "- [ ] hemp",
].join("\n");

describe("journal file", () => {
  it("reads every field the phone writes", () => {
    const entry = parseJournalFile(42, ANDROID_JOURNAL);
    expect(entry.id).toBe(42);
    expect(entry.content).toBe("Wrote this on the phone.");
    expect(entry.prompt).toBe("What did today take out of you?");
    expect(entry.isPrivate).toBe(false);
    expect(entry.createdAt).toBe(1755600000000);
    expect(entry.attachments).toBe("1AbC,file:squat.mp4:2XyZ");
    expect(entry.bucket).toBe("Kink");
  });

  it("keeps the bucket through a round trip", () => {
    // The bug: the web app parsed no bucket and wrote none, so editing an entry on the laptop
    // stripped the comment. The current phone survives that (it keeps its local bucket on a
    // blank comment) but a *new* device restored the entry unfiled — and if that bucket was a
    // vault bucket, the entry came back visible.
    const entry = parseJournalFile(42, ANDROID_JOURNAL);
    const rewritten = serialiseJournalFile(entry, 1755600002000);
    expect(rewritten).toContain("<!-- bucket: Kink -->");
    expect(parseJournalFile(42, rewritten).bucket).toBe("Kink");
  });

  it("keeps attachments through a round trip", () => {
    const entry = parseJournalFile(42, ANDROID_JOURNAL);
    const rewritten = serialiseJournalFile(entry, 1755600002000);
    expect(parseJournalFile(42, rewritten).attachments).toBe(
      "1AbC,file:squat.mp4:2XyZ"
    );
  });

  it("advances updatedAt on every write, so the phone can tell which copy is newer", () => {
    const entry = parseJournalFile(42, ANDROID_JOURNAL);
    const rewritten = serialiseJournalFile(entry, 1755600002000);
    expect(rewritten).toContain("<!-- updatedAt: 1755600002000 -->");
  });

  it("does not let a prompt containing --> escape its comment", () => {
    const entry = parseJournalFile(1, "<!-- createdAt: 1 -->\n\nbody");
    entry.prompt = "why --> this";
    const rewritten = serialiseJournalFile({ ...entry, prompt: "why --> this" }, 1);
    // The body must still be the body: an unescaped --> would end the comment early and spill
    // the rest of the prompt into the entry text.
    expect(parseJournalFile(1, rewritten).content).toBe("body");
  });

  it("reports a deletion stamp so the entry can be withheld", () => {
    const deleted = stampDeleted(ANDROID_JOURNAL, 1755600009000);
    expect(parseJournalFile(42, deleted).deletedAt).toBe(1755600009000);
  });

  it("treats a missing createdAt as 0 rather than now", () => {
    // Falling back to now would float a malformed entry to the top of the list on every load,
    // which reads as the entry being silently rewritten.
    expect(parseJournalFile(7, "no metadata here").createdAt).toBe(0);
  });
});

describe("note file", () => {
  it("reads title, bucket, attachments and body", () => {
    const note = parseNoteFile("12", ANDROID_NOTE);
    expect(note.title).toBe("Rope inventory");
    expect(note.bucket).toBe("Kink");
    expect(note.attachments).toBe("9ZzZ");
    expect(note.content).toBe("- [x] jute\n- [ ] hemp");
  });

  it("keeps attachments through a round trip", () => {
    // Before wire format v2 the web app rewrote notes without this comment, orphaning every
    // photo added on the phone: the files stayed in Drive with nothing referencing them.
    const note = parseNoteFile("12", ANDROID_NOTE);
    const rewritten = serialiseNoteFile(
      note.title,
      note.content,
      note.bucket,
      note.attachments,
      1755600002000
    );
    expect(parseNoteFile("12", rewritten).attachments).toBe("9ZzZ");
  });

  it("reports an unknown bucket as empty, never as a real one", () => {
    // The old parser defaulted to "Personal". An untitled note in a vault bucket therefore
    // rendered while the vault was locked, and was permanently relabelled if edited.
    const untitled = "<!-- updatedAt: 1 -->\n\njust a body";
    expect(parseNoteFile("13", untitled).bucket).toBe("");
  });

  it("writes no bucket comment when the bucket is unknown", () => {
    // Writing `<!-- bucket:  -->` or inventing one would turn "unknown" into a claim.
    const out = serialiseNoteFile("T", "body", "", "", 1);
    expect(out).not.toContain("bucket:");
    expect(parseNoteFile("14", out).bucket).toBe("");
  });

  it("does not glue metadata comments onto the body", () => {
    // The original bug: a single-line skip left the second comment as the note's first line.
    const note = parseNoteFile("12", ANDROID_NOTE);
    expect(note.content.startsWith("<!--")).toBe(false);
  });

  it("survives a title containing a hash", () => {
    const out = serialiseNoteFile("Meeting #3", "body", "Work", "", 1);
    expect(parseNoteFile("15", out).title).toBe("Meeting #3");
  });
});

describe("deletion stamping", () => {
  it("does not stack markers when applied twice", () => {
    const once = stampDeleted(ANDROID_NOTE, 1000);
    const twice = stampDeleted(once, 2000);
    expect(twice.match(/deletedAt/g)?.length).toBe(1);
    expect(parseNoteFile("12", twice).deletedAt).toBe(2000);
  });

  it("preserves the content it marks", () => {
    // The file stays on Drive so the phone can soft-delete and keep it recoverable for 90 days.
    // If stamping lost the body, "restore" would restore an empty note.
    const stamped = stampDeleted(ANDROID_NOTE, 1000);
    expect(parseNoteFile("12", stamped).content).toContain("- [x] jute");
    expect(parseNoteFile("12", stamped).bucket).toBe("Kink");
  });
});

// ─── Chat threads ──────────────────────────────────────────────────────────────
//
// A conversation is the one file format where the body can contain anything at all — Claude
// writes code, and code contains HTML comments — so the parser cannot use the whole-file
// comment strip that notes and journal entries use. These tests pin that down, because the
// failure mode is silent: half an answer disappears and nothing reports it.

describe("chat threads", () => {
  const thread: ChatThreadDto = {
    id: 7,
    title: "Sunday session",
    createdAt: 1_000,
    updatedAt: 2_000,
    messages: [
      { content: "How did I go?", isFromUser: true, createdAt: 1_100 },
      { content: "Strong.", isFromUser: false, createdAt: 1_200 },
    ],
  };

  it("round-trips a conversation with roles and timestamps intact", () => {
    const parsed = parseChatFile(7, serialiseChatFile(thread, 2_000));
    expect(parsed.title).toBe("Sunday session");
    expect(parsed.createdAt).toBe(1_000);
    expect(parsed.updatedAt).toBe(2_000);
    expect(parsed.messages).toEqual(thread.messages);
  });

  it("keeps an HTML comment inside a message", () => {
    // The whole reason messages are delimited rather than comment-stripped. Claude answering
    // with a snippet of HTML must not have the snippet eaten.
    const withCode: ChatThreadDto = {
      ...thread,
      messages: [
        {
          content: "Try:\n<!-- keep me -->\n<div>hi</div>",
          isFromUser: false,
          createdAt: 1_300,
        },
      ],
    };
    const parsed = parseChatFile(7, serialiseChatFile(withCode, 2_000));
    expect(parsed.messages[0].content).toBe("Try:\n<!-- keep me -->\n<div>hi</div>");
  });

  it("does not read metadata out of a message body", () => {
    // Written by hand rather than through serialiseChatFile, because that always emits a
    // title comment and the emitted one wins by position anyway. The case this guards is a
    // file whose header omits a field — another writer, or a future format — where scanning
    // the whole file would take the title and the date out of whatever Claude happened to
    // say, and the conversation would rename and re-date itself.
    const raw = [
      "<!-- createdAt: 1000 -->",
      "<!-- updatedAt: 2000 -->",
      "",
      "<!-- msg: assistant 1400 -->",
      "<!-- title: Something else -->",
      "<!-- createdAt: 999999 -->",
    ].join("\n");
    const parsed = parseChatFile(7, raw);
    expect(parsed.title).toBe("");
    expect(parsed.createdAt).toBe(1_000);
  });

  it("escapes a comment terminator in the title", () => {
    // An unescaped --> would close the comment early and spill the rest into the transcript.
    const raw = serialiseChatFile({ ...thread, title: "a --> b" }, 2_000);
    expect(raw).not.toContain("a --> b -->");
    expect(parseChatFile(7, raw).messages).toHaveLength(2);
  });

  it("reads a delete stamp, so a web delete is not mistaken for an empty thread", () => {
    const stamped = stampDeleted(serialiseChatFile(thread, 2_000), 3_000);
    const parsed = parseChatFile(7, stamped);
    expect(parsed.deletedAt).toBe(3_000);
    // Still parseable: the phone needs the id and the stamp, not a wreck.
    expect(parsed.messages).toHaveLength(2);
  });

  it("yields no messages for a file with no delimiters rather than one giant message", () => {
    const parsed = parseChatFile(7, "<!-- title: Empty -->\n<!-- createdAt: 1 -->\n");
    expect(parsed.messages).toEqual([]);
  });
});
