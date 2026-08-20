"use client";

import { useState, useEffect, useCallback } from "react";
import { useVault } from "@/hooks/useVault";
import type { JournalEntryDto } from "@/lib/drive";

function formatWhen(ms: number): string {
  if (!ms) return "Unknown date";
  return new Date(ms).toLocaleString("en-AU", {
    weekday: "short",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

export default function JournalList() {
  // Vault state goes to the server, which withholds private entries entirely while closed.
  const { isVaultOpen } = useVault();
  const [entries, setEntries] = useState<JournalEntryDto[]>([]);
  const [hiddenCount, setHiddenCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [text, setText] = useState("");
  const [isPrivate, setIsPrivate] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);

  const fetchEntries = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(
        `/api/drive/journal${isVaultOpen ? "?vault=open" : ""}`
      );
      if (!res.ok) throw new Error("Failed to fetch");
      const data = await res.json();
      setEntries(data.entries ?? []);
      setHiddenCount(data.hiddenCount ?? 0);
    } catch {
      setError("Failed to load journal entries.");
    } finally {
      setLoading(false);
    }
  }, [isVaultOpen]);

  useEffect(() => {
    fetchEntries();
  }, [fetchEntries]);

  async function save() {
    if (!text.trim()) return;
    setSaving(true);
    setError(null);
    try {
      const existing = editingId
        ? entries.find((e) => e.id === editingId)
        : undefined;
      const res = await fetch("/api/drive/journal", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id: editingId ?? undefined,
          content: text.trim(),
          isPrivate,
          // Editing keeps the original prompt and timestamp — an entry rewritten on the
          // laptop should not lose the question it was answering or jump to today.
          prompt: existing?.prompt ?? "",
          createdAt: existing?.createdAt,
          // Echoed back so editing here does not strip attachments added on the phone.
          attachments: existing?.attachments ?? "",
        }),
      });
      if (!res.ok) throw new Error("Failed to save");
      setText("");
      setIsPrivate(false);
      setEditingId(null);
      await fetchEntries();
    } catch {
      setError("Failed to save entry.");
    } finally {
      setSaving(false);
    }
  }

  async function remove(id: number) {
    setError(null);
    try {
      const res = await fetch(`/api/drive/journal?id=${id}`, { method: "DELETE" });
      if (!res.ok) throw new Error("Failed to delete");
      setEntries((prev) => prev.filter((e) => e.id !== id));
    } catch {
      setError("Failed to delete entry.");
    }
  }

  function startEdit(entry: JournalEntryDto) {
    setEditingId(entry.id);
    setText(entry.content);
    setIsPrivate(entry.isPrivate);
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function cancelEdit() {
    setEditingId(null);
    setText("");
    setIsPrivate(false);
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-[#E6E1E5]">Journal</h1>
        <p className="text-sm text-[#938F99]">
          {entries.length} {entries.length === 1 ? "entry" : "entries"}
          {hiddenCount > 0 && ` · ${hiddenCount} private hidden`}
        </p>
      </div>

      {/* Composer */}
      <div className="bg-[#2B2930] rounded-2xl border border-[#49454F] p-6 space-y-4">
        <h2 className="text-lg font-semibold text-[#E6E1E5]">
          {editingId ? "Edit entry" : "New entry"}
        </h2>
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Write whatever is there."
          rows={6}
          className="w-full px-4 py-3 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4] resize-y"
        />
        <div className="flex items-center justify-between gap-3">
          <label className="flex items-center gap-2 text-sm text-[#CAC4D0] cursor-pointer">
            <input
              type="checkbox"
              checked={isPrivate}
              onChange={(e) => setIsPrivate(e.target.checked)}
              className="w-4 h-4 accent-[#6750A4]"
            />
            Private
          </label>
          <div className="flex gap-3">
            {editingId && (
              <button
                onClick={cancelEdit}
                className="px-4 py-2.5 rounded-xl border border-[#49454F] text-[#CAC4D0] hover:bg-[#49454F]/40 transition-colors"
              >
                Cancel
              </button>
            )}
            <button
              onClick={save}
              disabled={!text.trim() || saving}
              className="px-5 py-2.5 rounded-xl bg-[#6750A4] text-white hover:bg-[#7965AF] transition-colors font-medium disabled:opacity-40"
            >
              {saving ? "Saving…" : editingId ? "Save changes" : "Save entry"}
            </button>
          </div>
        </div>
        {isPrivate && (
          <p className="text-xs text-[#938F99]">
            Private entries are hidden unless the vault is shown, and are never sent to Claude.
          </p>
        )}
      </div>

      {error && <p className="text-sm text-[#F2B8B5]">{error}</p>}

      {loading ? (
        <p className="text-sm text-[#938F99]">Loading…</p>
      ) : entries.length === 0 ? (
        <p className="text-sm text-[#938F99]">
          Nothing written yet. Entries from your phone appear here too.
        </p>
      ) : (
        <div className="space-y-3">
          {entries.map((entry) => (
            <div
              key={entry.id}
              className="bg-[#2B2930] rounded-2xl border border-[#49454F] p-5 space-y-2"
            >
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <p className="text-xs text-[#938F99]">
                    {formatWhen(entry.createdAt)}
                    {entry.isPrivate && " · Private"}
                  </p>
                  {entry.prompt && (
                    <p className="text-xs italic text-[#938F99] mt-1">
                      {entry.prompt}
                    </p>
                  )}
                </div>
                <div className="flex gap-2 shrink-0">
                  <button
                    onClick={() => startEdit(entry)}
                    className="text-xs px-3 py-1.5 rounded-lg border border-[#49454F] text-[#CAC4D0] hover:bg-[#49454F]/40 transition-colors"
                  >
                    Edit
                  </button>
                  <button
                    onClick={() => remove(entry.id)}
                    className="text-xs px-3 py-1.5 rounded-lg border border-[#49454F] text-[#F2B8B5] hover:bg-[#49454F]/40 transition-colors"
                  >
                    Delete
                  </button>
                </div>
              </div>
              <p className="text-sm text-[#E6E1E5] whitespace-pre-wrap">
                {entry.content}
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
