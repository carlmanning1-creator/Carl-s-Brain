// ─── Drive / Todo types ───────────────────────────────────────────────────────

/**
 * The todo wire format shared with the Android client (DriveSyncWorker.TodoSyncDto).
 *
 * Keep the two in step. Fields the phone writes but this interface omits are dropped whenever
 * the web app saves a todo, because saving rewrites the whole todos.json entry — so an omission
 * here is silent data loss, not merely a missing feature.
 *
 * Optional fields are optional because older todos.json files predate them.
 */
export interface TodoSyncDto {
  id: number;
  title: string;
  bucket: string;
  priority: "URGENT" | "HIGH" | "NORMAL" | "SOMEDAY";
  isDone: boolean;
  dueDate: number | null;
  recurrence?: "DAILY" | "WEEKLY" | "MONTHLY" | "FORTNIGHTLY" | "";
  leadDays?: number;
  /** Epoch ms for a reminder. Set on the phone; preserved rather than edited here. */
  reminderAt?: number | null;
  isPinned?: boolean;
  /** Rough minutes the todo takes. Drives the phone's "what fits right now". */
  estimateMinutes?: number | null;
  createdAt: number;
  updatedAt: number;
  deletedAt: number | null;
}

export interface NoteDto {
  id: string;
  title: string;
  content: string;
  bucket: string;
  createdAt?: number;
  updatedAt?: number;
  driveFileId?: string;
}

// ─── Bucket config ─────────────────────────────────────────────────────────────

export interface BucketConfig {
  name: string;
  isVault: boolean;
}

export const DEFAULT_BUCKETS: BucketConfig[] = [
  { name: "SES", isVault: false },
  { name: "Family", isVault: false },
  { name: "Work", isVault: false },
  { name: "Personal", isVault: false },
  { name: "Kink", isVault: true },
  { name: "Other", isVault: false },
];

export const VAULT_BUCKETS = DEFAULT_BUCKETS.filter((b) => b.isVault).map(
  (b) => b.name
);

// ─── Calendar types ────────────────────────────────────────────────────────────

export interface CalendarEvent {
  id: string;
  title: string;
  start: string;
  end: string;
  allDay: boolean;
  location?: string;
  description?: string;
  calendarName?: string;
}

// ─── Chat types ────────────────────────────────────────────────────────────────

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

// ─── Meeting types ─────────────────────────────────────────────────────────────

export interface ActionItem {
  title: string;
  bucket: string;
}

export interface Meeting {
  id: string;
  folderName: string;
  title: string;
  recordedAt: number;
  durationMs: number;
  /** Bucket name from meta.json; empty when unsorted or written by an older client. */
  bucket: string;
  /** Non-null while the phone has this meeting in Recently Deleted — hidden from the list. */
  deletedAt?: number | null;
  transcript: string;
  summary: string;
  actionItems: ActionItem[];
  status: "DONE" | "AUDIO_ONLY" | "NO_TRANSCRIPT";
}

// ─── NextAuth session extension ────────────────────────────────────────────────

declare module "next-auth" {
  interface Session {
    accessToken?: string;
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    accessToken?: string;
  }
}
