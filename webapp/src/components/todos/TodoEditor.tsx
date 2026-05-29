"use client";

import { useState, useEffect } from "react";
import { DEFAULT_BUCKETS } from "@/lib/types";
import type { TodoSyncDto } from "@/lib/types";

interface TodoEditorProps {
  todo: TodoSyncDto | null;
  onSave: (todo: TodoSyncDto) => Promise<boolean>;
  onClose: () => void;
}

export default function TodoEditor({ todo, onSave, onClose }: TodoEditorProps) {
  const [title, setTitle] = useState(todo?.title ?? "");
  const [bucket, setBucket] = useState(todo?.bucket ?? "Personal");
  const [priority, setPriority] = useState<TodoSyncDto["priority"]>(
    todo?.priority ?? "NORMAL"
  );
  const [dueDate, setDueDate] = useState<string>(
    todo?.dueDate
      ? new Date(todo.dueDate).toISOString().split("T")[0]
      : ""
  );
  const [recurrence, setRecurrence] = useState<TodoSyncDto["recurrence"]>(todo?.recurrence ?? "");
  const [leadDays, setLeadDays] = useState<number>(todo?.leadDays ?? 0);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setTitle(todo?.title ?? "");
    setBucket(todo?.bucket ?? "Personal");
    setPriority(todo?.priority ?? "NORMAL");
    setDueDate(
      todo?.dueDate
        ? new Date(todo.dueDate).toISOString().split("T")[0]
        : ""
    );
    setRecurrence(todo?.recurrence ?? "");
    setLeadDays(todo?.leadDays ?? 0);
  }, [todo]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setSaving(true);

    const now = Date.now();
    const updated: TodoSyncDto = {
      id: todo?.id ?? now,
      title: title.trim(),
      bucket,
      priority,
      isDone: todo?.isDone ?? false,
      dueDate: dueDate ? new Date(dueDate).getTime() : null,
      recurrence: recurrence || "",
      leadDays: recurrence ? leadDays : 0,
      createdAt: todo?.createdAt ?? now,
      updatedAt: now,
      deletedAt: null,
    };

    const ok = await onSave(updated);
    setSaving(false);
    if (ok) onClose();
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        onClick={onClose}
      />
      <div className="relative z-10 w-full max-w-md bg-[#2B2930] border border-[#49454F] rounded-2xl p-6 shadow-2xl">
        <h2 className="text-lg font-semibold text-[#E6E1E5] mb-5">
          {todo ? "Edit Todo" : "New Todo"}
        </h2>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
              Title <span className="text-red-400">*</span>
            </label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="What needs to be done?"
              autoFocus
              className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4]"
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
                Priority
              </label>
              <select
                value={priority}
                onChange={(e) =>
                  setPriority(e.target.value as TodoSyncDto["priority"])
                }
                className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
              >
                <option value="URGENT">Urgent</option>
                <option value="HIGH">High</option>
                <option value="NORMAL">Normal</option>
                <option value="SOMEDAY">Someday</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
                Bucket
              </label>
              <select
                value={bucket}
                onChange={(e) => setBucket(e.target.value)}
                className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
              >
                {DEFAULT_BUCKETS.map((b) => (
                  <option key={b.name} value={b.name}>
                    {b.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
                Due Date (optional)
              </label>
              <input
                type="date"
                value={dueDate}
                onChange={(e) => setDueDate(e.target.value)}
                className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
                Repeat
              </label>
              <select
                value={recurrence ?? ""}
                onChange={(e) => setRecurrence(e.target.value as TodoSyncDto["recurrence"])}
                className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
              >
                <option value="">No repeat</option>
                <option value="DAILY">Daily</option>
                <option value="WEEKLY">Weekly</option>
                <option value="MONTHLY">Monthly</option>
              </select>
            </div>
          </div>

          {recurrence && (
            <div>
              <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
                Remind me
              </label>
              <select
                value={leadDays}
                onChange={(e) => setLeadDays(Number(e.target.value))}
                className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
              >
                <option value={0}>On due date</option>
                <option value={1}>1 day before</option>
                <option value={3}>3 days before</option>
                <option value={7}>7 days before</option>
                <option value={14}>14 days before</option>
              </select>
            </div>
          )}

          <div className="flex gap-3 pt-1">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 rounded-xl border border-[#49454F] text-[#CAC4D0] hover:bg-[#49454F]/40 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving || !title.trim()}
              className="flex-1 px-4 py-2.5 rounded-xl bg-[#6750A4] text-white hover:bg-[#7965AF] disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium flex items-center justify-center gap-2"
            >
              {saving ? (
                <>
                  <svg
                    className="w-4 h-4 animate-spin"
                    fill="none"
                    viewBox="0 0 24 24"
                  >
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                    />
                  </svg>
                  Saving...
                </>
              ) : (
                "Save"
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
