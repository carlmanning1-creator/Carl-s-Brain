import { describe, expect, it } from "vitest";
import { escapeDriveQueryValue, isVaultBucket, validEntityId } from "./driveQuery";

/**
 * These two guard the routes that accept a caller-supplied Drive id.
 *
 * The app's OAuth token has full `drive` scope, so a route that interpolates an unvalidated id
 * into a query can be pointed at any file in the account — memory.md included. Both functions
 * are three lines long, which is exactly why they are worth pinning down: they look trivial
 * enough to "simplify" later.
 */

describe("escapeDriveQueryValue", () => {
  it("escapes the quote that would end the literal", () => {
    // The attack: an id of `x.md' or name = 'memory.md` turns the rest of the query into
    // syntax, so a route asked about a note matches memory.md instead.
    const escaped = escapeDriveQueryValue("x.md' or name = 'memory.md");
    expect(escaped).not.toMatch(/(^|[^\\])'/);
  });

  it("escapes backslashes before quotes, not after", () => {
    // Escaping in the wrong order re-escapes the backslashes it just added, which leaves the
    // quote unescaped again.
    expect(escapeDriveQueryValue("a\\'b")).toBe("a\\\\\\'b");
  });

  it("leaves ordinary values untouched", () => {
    expect(escapeDriveQueryValue("note_12.md")).toBe("note_12.md");
    expect(escapeDriveQueryValue("SecondBrain")).toBe("SecondBrain");
  });
});

describe("validEntityId", () => {
  it("accepts the ids both clients actually produce", () => {
    // Room autoincrement on the phone, epoch milliseconds on the web.
    expect(validEntityId("12")).toBe("12");
    expect(validEntityId("1755600000000")).toBe("1755600000000");
    expect(validEntityId(" 12 ")).toBe("12");
  });

  it("refuses anything that is not an integer", () => {
    expect(validEntityId("x.md' or name = 'memory.md")).toBeNull();
    expect(validEntityId("../../memory")).toBeNull();
    expect(validEntityId("12.md")).toBeNull();
    expect(validEntityId("-1")).toBeNull();
    expect(validEntityId("1e5")).toBeNull();
  });

  it("refuses absent input rather than defaulting", () => {
    expect(validEntityId(null)).toBeNull();
    expect(validEntityId(undefined)).toBeNull();
    expect(validEntityId("")).toBeNull();
  });
});

describe("isVaultBucket", () => {
  const vault = ["Kink", "Personal Health"];

  it("matches exactly", () => {
    expect(isVaultBucket("Kink", vault)).toBe(true);
  });

  it("ignores case, because the Android client does", () => {
    // The bug: the to-do and meetings lists used Array.includes, so a bucket written as "kink"
    // on a to-do did not match "Kink" in buckets.json and the item was shown.
    expect(isVaultBucket("kink", vault)).toBe(true);
    expect(isVaultBucket("KINK", vault)).toBe(true);
  });

  it("ignores surrounding whitespace on both sides", () => {
    expect(isVaultBucket(" Kink ", vault)).toBe(true);
    expect(isVaultBucket("Kink", [" Kink "])).toBe(true);
  });

  it("treats an absent or empty bucket as not vault", () => {
    // Unfiled is not hidden — callers that need "unknown means withhold" check that themselves.
    expect(isVaultBucket("", vault)).toBe(false);
    expect(isVaultBucket(null, vault)).toBe(false);
    expect(isVaultBucket(undefined, vault)).toBe(false);
    expect(isVaultBucket("   ", vault)).toBe(false);
  });

  it("does not match a different bucket", () => {
    expect(isVaultBucket("Work", vault)).toBe(false);
    expect(isVaultBucket("Kinky", vault)).toBe(false);
  });
});
