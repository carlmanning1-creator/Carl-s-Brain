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
export function nextDueDate(
  baseMs: number | null,
  recurrence: NonNullable<TodoSyncDto["recurrence"]>
): number | null {
  const DAY = 24 * 60 * 60 * 1000;
  const from = baseMs ?? Date.now();
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
    default:
      return null;
  }
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
