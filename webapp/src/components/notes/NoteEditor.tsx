"use client";

import { useState, useEffect } from "react";
import { DEFAULT_BUCKETS } from "@/lib/types";
import type { NoteDto } from "@/lib/types";

interface NoteEditorProps {
  note: NoteDto | null;
  onSave: (id: string, title: string, content: string, bucket: string) => Promise<boolean>;
  onDelete?: (id: string) => Promise<boolean>;
  onClose: () => void;
}

export default function NoteEditor({
  note,
  onSave,
  onDelete,
  onClose,
}: NoteEditorProps) {
  const [title, setTitle] = useState(note?.title ?? "");
  const [content, setContent] = useState(note?.content ?? "");
  const [bucket, setBucket] = useState(note?.bucket ?? "Personal");
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    setTitle(note?.title ?? "");
    setContent(note?.content ?? "");
    setBucket(note?.bucket ?? "Personal");
    setDirty(false);
  }, [note]);

  const noteId = note?.id ?? `${Date.now()}`;

  async function handleSave() {
    if (!title.trim()) return;
    setSaving(true);
    const ok = await onSave(noteId, title.trim(), content, bucket);
    setSaving(false);
    if (ok) {
      setDirty(false);
    }
  }

  async function handleDelete() {
    if (!note || !onDelete) return;
    if (!confirm(`Delete "${note.title}"?`)) return;
    const ok = await onDelete(note.id);
    if (ok) onClose();
  }

  return (
    <div className="flex flex-col h-full">
      {/* Toolbar */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-[#49454F]">
        <button
          onClick={onClose}
          className="text-[#938F99] hover:text-[#CAC4D0] transition-colors"
          aria-label="Close"
        >
          <svg
            className="w-5 h-5"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M15 19l-7-7 7-7"
            />
          </svg>
        </button>

        <select
          value={bucket}
          onChange={(e) => {
            setBucket(e.target.value);
            setDirty(true);
          }}
          className="text-xs bg-[#49454F]/40 border border-[#49454F] rounded-lg px-2 py-1.5 text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
        >
          {DEFAULT_BUCKETS.map((b) => (
            <option key={b.name} value={b.name}>
              {b.name}
            </option>
          ))}
        </select>

        <div className="flex-1" />

        {note && onDelete && (
          <button
            onClick={handleDelete}
            className="text-[#938F99] hover:text-[#F2B8B5] transition-colors p-1"
            title="Delete note"
          >
            <svg
              className="w-4 h-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
              />
            </svg>
          </button>
        )}

        <button
          onClick={handleSave}
          disabled={saving || !title.trim()}
          className="px-3 py-1.5 bg-[#6750A4] text-white rounded-lg text-sm font-medium hover:bg-[#7965AF] disabled:opacity-50 disabled:cursor-not-allowed transition-colors flex items-center gap-1.5"
        >
          {saving ? (
            <>
              <svg
                className="w-3.5 h-3.5 animate-spin"
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
              Saving
            </>
          ) : (
            <>
              {dirty ? "Save*" : "Save"}
            </>
          )}
        </button>
      </div>

      {/* Title */}
      <input
        type="text"
        value={title}
        onChange={(e) => {
          setTitle(e.target.value);
          setDirty(true);
        }}
        placeholder="Note title"
        className="px-6 py-4 text-xl font-bold bg-transparent text-[#E6E1E5] placeholder-[#49454F] border-b border-[#49454F] focus:outline-none focus:border-[#6750A4]"
      />

      {/* Content */}
      <textarea
        value={content}
        onChange={(e) => {
          setContent(e.target.value);
          setDirty(true);
        }}
        placeholder="Start writing... (Markdown supported)"
        className="flex-1 px-6 py-4 bg-transparent text-[#E6E1E5] placeholder-[#49454F] resize-none focus:outline-none font-mono text-sm leading-relaxed"
      />

      {/* Footer metadata */}
      {note?.updatedAt && (
        <div className="px-6 py-2 border-t border-[#49454F]">
          <p className="text-xs text-[#938F99]">
            Last updated:{" "}
            {new Date(note.updatedAt).toLocaleString("en-AU")}
          </p>
        </div>
      )}
    </div>
  );
}
