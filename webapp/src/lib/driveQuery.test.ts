import { describe, expect, it } from "vitest";
import { escapeDriveQueryValue, validEntityId } from "./driveQuery";

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
