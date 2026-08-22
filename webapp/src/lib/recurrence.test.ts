import { describe, expect, it } from "vitest";
import { nextDueDate, spawnNextOccurrence } from "./recurrence";
import type { TodoSyncDto } from "./types";

const DAY = 24 * 60 * 60 * 1000;
const NOW = 1755600000000;

function todo(over: Partial<TodoSyncDto> = {}): TodoSyncDto {
  return {
    id: 1,
    title: "Weekly pump check",
    bucket: "SES",
    priority: "NORMAL",
    isDone: true,
    dueDate: NOW,
    createdAt: NOW - 7 * DAY,
    updatedAt: NOW,
    deletedAt: null,
    recurrence: "WEEKLY",
    ...over,
  };
}

describe("nextDueDate", () => {
  it("advances by the right interval", () => {
    expect(nextDueDate(NOW, "DAILY")).toBe(NOW + DAY);
    expect(nextDueDate(NOW, "WEEKLY")).toBe(NOW + 7 * DAY);
    expect(nextDueDate(NOW, "FORTNIGHTLY")).toBe(NOW + 14 * DAY);
  });

  it("advances a month by calendar, not by 30 days", () => {
    // 31 Jan + 1 month must not silently become 2 March in a way that surprises; using the
    // calendar keeps it consistent with the phone, which uses Calendar.add(MONTH, 1).
    const jan15 = new Date("2026-01-15T09:00:00Z").getTime();
    const feb15 = new Date("2026-02-15T09:00:00Z").getTime();
    expect(nextDueDate(jan15, "MONTHLY")).toBe(feb15);
  });

  it("returns null for a non-recurring to-do", () => {
    expect(nextDueDate(NOW, "")).toBeNull();
  });

  it("counts from now when the to-do has no due date", () => {
    const spawned = nextDueDate(null, "DAILY");
    expect(spawned).not.toBeNull();
    expect(spawned!).toBeGreaterThan(Date.now());
  });
});

describe("spawnNextOccurrence", () => {
  it("creates the next occurrence when a recurring to-do is ticked off", () => {
    const next = spawnNextOccurrence(todo(), [todo()], NOW);
    expect(next).not.toBeNull();
    expect(next!.isDone).toBe(false);
    expect(next!.dueDate).toBe(NOW + 7 * DAY);
    expect(next!.title).toBe("Weekly pump check");
  });

  it("creates nothing for a one-off", () => {
    expect(spawnNextOccurrence(todo({ recurrence: "" }), [], NOW)).toBeNull();
  });

  it("does not spawn a duplicate when an occurrence is already open", () => {
    // Matches findActiveRecurringByTitleAndRecurrence on the phone. Without it, two ticks —
    // or a tick synced from both clients — would leave Carl with the same task twice.
    const done = todo({ id: 1 });
    const alreadyOpen = todo({ id: 2, isDone: false });
    expect(spawnNextOccurrence(done, [done, alreadyOpen], NOW)).toBeNull();
  });

  it("starts the new occurrence with no subtasks", () => {
    // The phone's spawnNextRecurrence copies the to-do row, and subtasks live in their own
    // table — so a fresh occurrence begins un-ticked. Inheriting a half-finished checklist
    // would also make the loose-thread detector treat a brand new task as stalled work.
    const withSteps = todo({
      subtasks: [
        { title: "drain", isDone: true, sortOrder: 0 },
        { title: "refill", isDone: false, sortOrder: 1 },
      ],
    });
    const next = spawnNextOccurrence(withSteps, [withSteps], NOW);
    expect(next!.subtasks).toEqual([]);
  });

  it("carries attachments across but never the archive state", () => {
    const archived = todo({ attachments: "1AbC", isArchived: true, archivedAt: NOW });
    const next = spawnNextOccurrence(archived, [archived], NOW);
    expect(next!.attachments).toBe("1AbC");
    expect(next!.isArchived).toBe(false);
    expect(next!.archivedAt).toBeNull();
  });

  it("shifts the reminder by the same interval", () => {
    const withReminder = todo({ reminderAt: NOW - DAY });
    const next = spawnNextOccurrence(withReminder, [withReminder], NOW);
    expect(next!.reminderAt).toBe(NOW - DAY + 7 * DAY);
  });

  it("uses lead days when set, in preference to shifting the old reminder", () => {
    const withLead = todo({ leadDays: 2, reminderAt: NOW - DAY });
    const next = spawnNextOccurrence(withLead, [withLead], NOW);
    expect(next!.reminderAt).toBe(NOW + 7 * DAY - 2 * DAY);
  });

  it("gives the occurrence an id nothing else is using", () => {
    // The parent may itself have been created in this same request with an epoch-ms id.
    const collide = todo({ id: NOW });
    const next = spawnNextOccurrence(collide, [collide], NOW);
    expect(next!.id).not.toBe(NOW);
  });

  it("stamps the current schema so its nulls are read as deliberate", () => {
    const next = spawnNextOccurrence(todo(), [todo()], NOW);
    expect(next!.schema).toBe(2);
  });
});
