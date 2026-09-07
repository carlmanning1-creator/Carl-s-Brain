import { TODO_SCHEMA_VERSION, type TodoSyncDto } from "./types";

/**
 * Recurrence, as pure functions.
 *
 * Deliberately mirrors CompleteTodoUseCase on the phone: two implementations of the same rules
 * that disagree produce either a missing occurrence or a duplicate, and both fail silently. That
 * is exactly what happened before this existed — ticking a recurring to-do off on the web ended
 * the chain, because only the phone knew how to spawn the next one.
 *
 * Tested here rather than through the route so the interval maths, the idempotency guard and the
 * "a new occurrence starts fresh" rules are pinned down without needing Drive.
 */

/**
 * The next due date for a recurring to-do, mirroring CompleteTodoUseCase.nextDateMs on the
 * phone. Kept deliberately in step with it: two implementations of recurrence that disagree
 * produce either a missing occurrence or a duplicate, and both fail silently.
 */
/** One interval on from `from`. Null for a recurrence with no next date. */
function stepOnce(
  from: number,
  recurrence: NonNullable<TodoSyncDto["recurrence"]>
): number | null {
  const DAY = 24 * 60 * 60 * 1000;
  switch (recurrence) {
    case "DAILY":
      return from + DAY;
    case "WEEKLY":
      return from + 7 * DAY;
    case "FORTNIGHTLY":
      return from + 14 * DAY;
    case "MONTHLY": {
      const d = new Date(from);
      d.setMonth(d.getMonth() + 1);
      return d.getTime();
    }
    default: {
      // CUSTOM:<days>, which this file did not handle at all — so a custom-interval to-do
      // ticked off on the laptop returned null and the chain simply ended.
      if (recurrence.startsWith("CUSTOM:")) {
        const days = Number(recurrence.slice("CUSTOM:".length));
        if (!Number.isFinite(days) || days <= 0) return null;
        return from + days * DAY;
      }
      return null;
    }
  }
}

/**
 * How many intervals the catch-up loop will step. Mirrors
 * CompleteTodoUseCase.MAX_CATCH_UP_STEPS.
 */
const MAX_CATCH_UP_STEPS = 2000;

export function nextDueDate(
  baseMs: number | null,
  recurrence: NonNullable<TodoSyncDto["recurrence"]>
): number | null {
  const now = Date.now();
  let from = baseMs ?? now;
  let next = stepOnce(from, recurrence);
  if (next === null) return null;

  // Step until the date is actually in the future, exactly as the phone does.
  //
  // This used to take a single step from the old due date, so completing something three weeks
  // overdue produced another already-overdue occurrence — forever. A task Carl had fallen
  // behind on could never be caught up; it just re-presented itself as failure. This file's own
  // header says it mirrors CompleteTodoUseCase and that a disagreement fails silently, and this
  // was the disagreement.
  let guard = 0;
  while (next <= now && guard < MAX_CATCH_UP_STEPS) {
    from = next;
    const stepped = stepOnce(from, recurrence);
    if (stepped === null) return next;
    next = stepped;
    guard++;
  }
  return next;
}

/**
 * Spawns the next occurrence when a recurring to-do is ticked off, the way the phone does.
 *
 * Without this, ticking a weekly task from the laptop ended the chain silently: the phone's
 * pull applies the update directly and never runs the completion use case, so nothing anywhere
 * created the next one.
 *
 * @returns the occurrence to append, or null when nothing should be spawned.
 */
export function spawnNextOccurrence(
  completed: TodoSyncDto,
  all: TodoSyncDto[],
  now: number
): TodoSyncDto | null {
  const recurrence = completed.recurrence;
  if (!recurrence) return null;

  // Idempotency, matching findActiveRecurringByTitleAndRecurrence on the phone: if an open
  // occurrence of the same task already exists, a second tick must not add another.
  const alreadyOpen = all.some(
    (t) =>
      !t.isDone &&
      t.deletedAt === null &&
      t.id !== completed.id &&
      t.title === completed.title &&
      t.recurrence === recurrence
  );
  if (alreadyOpen) return null;

  const nextDue = nextDueDate(completed.dueDate, recurrence);
  if (nextDue === null) return null;

  const intervalMs = nextDue - (completed.dueDate ?? now);
  const leadDays = completed.leadDays ?? 0;
  const nextReminder =
    leadDays > 0
      ? nextDue - leadDays * 24 * 60 * 60 * 1000
      : completed.reminderAt != null
        ? completed.reminderAt + intervalMs
        : null;

  // Epoch-ms ids, the same convention the journal route uses, so a web-created row cannot
  // collide with a Room autoincrement id from the phone. Stepped forward past any id already
  // in use, since the to-do being completed may itself have been created in this same request.
  let spawnId = now;
  while (all.some((t) => t.id === spawnId)) spawnId++;

  return {
    ...completed,
    schema: TODO_SCHEMA_VERSION,
    id: spawnId,
    isDone: false,
    dueDate: nextDue,
    reminderAt: nextReminder,
    createdAt: now,
    updatedAt: now,
    deletedAt: null,
    // Matches the phone: spawnNextRecurrence copies the to-do row, and subtasks live in their
    // own table, so a new occurrence starts with its steps un-ticked rather than inheriting a
    // half-finished list. Attachments do come along, exactly as they do there.
    subtasks: [],
    isArchived: false,
    archivedAt: null,
  };
}
