"use client";

import { useEffect, useState } from "react";
import { useVault } from "@/hooks/useVault";
import { useNotes } from "@/hooks/useNotes";
import { DEFAULT_BUCKETS } from "@/lib/types";
import type { NoteDto } from "@/lib/types";
import NoteEditor from "./NoteEditor";

export default function NotesList() {
  const { isVaultOpen } = useVault();
  // Vault state goes to the server, which decides what to send back.
  const { notes, loading, error, fetchNotes, saveNote, deleteNote } =
    useNotes(isVaultOpen);
  const [selectedNote, setSelectedNote] = useState<NoteDto | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedBucket, setSelectedBucket] = useState<string>("All");
  const [sortMode, setSortMode] = useState<"updated" | "created" | "alpha">("updated");

  useEffect(() => {
    fetchNotes();
  }, [fetchNotes]);

  const visibleBuckets = DEFAULT_BUCKETS.filter(
    (b) => !b.isVault || isVaultOpen
  );

  const filteredNotes = notes.filter((note) => {
    // No vault check here — the server withheld those notes entirely while locked.
    // Filter by bucket
    if (selectedBucket !== "All" && note.bucket !== selectedBucket)
      return false;
    // Filter by search
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      return (
        note.title.toLowerCase().includes(q) ||
        note.content.toLowerCase().includes(q)
      );
    }
    return true;
  });

  const sortedNotes = [...filteredNotes].sort((a, b) => {
    if (sortMode === "alpha") return a.title.localeCompare(b.title);
    if (sortMode === "created") return (b.createdAt ?? 0) - (a.createdAt ?? 0);
    return (b.updatedAt ?? b.createdAt ?? 0) - (a.updatedAt ?? a.createdAt ?? 0);
  });

  function openNew() {
    setSelectedNote(null);
    setIsEditing(true);
  }

  function openNote(note: NoteDto) {
    setSelectedNote(note);
    setIsEditing(true);
  }

  async function handleSave(
    id: string,
    title: string,
    content: string,
    bucket: string
  ) {
    const ok = await saveNote(id, title, content, bucket);
    if (ok && !selectedNote) {
      // New note was created — find it after a brief moment
      setTimeout(() => fetchNotes(), 500);
    }
    return ok;
  }

  if (isEditing) {
    return (
      <div className="flex flex-col h-[calc(100vh-4rem)] bg-[#2B2930] rounded-2xl border border-[#49454F] overflow-hidden">
        <NoteEditor
          note={selectedNote}
          onSave={handleSave}
          onDelete={deleteNote}
          onClose={() => {
            setIsEditing(false);
            setSelectedNote(null);
          }}
        />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold text-[#E6E1E5]">Notes</h1>
        <div className="flex items-center gap-2">
          <button
            onClick={fetchNotes}
            disabled={loading}
            className="p-2 text-[#938F99] hover:text-[#E6E1E5] disabled:opacity-40 transition-colors"
            title="Refresh"
          >
            <svg className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </button>
          <button
            onClick={openNew}
            className="flex items-center gap-2 px-4 py-2 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] transition-colors font-medium"
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
                d="M12 4v16m8-8H4"
              />
            </svg>
            New Note
          </button>
        </div>
      </div>

      {/* Search + Filter */}
      <div className="flex gap-3">
        <div className="flex-1 relative">
          <svg
            className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-[#938F99]"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search notes..."
            className="w-full pl-10 pr-4 py-2.5 bg-[#2B2930] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4]"
          />
        </div>
        <select
          value={selectedBucket}
          onChange={(e) => setSelectedBucket(e.target.value)}
          className="px-3 py-2.5 bg-[#2B2930] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
        >
          <option value="All">All Buckets</option>
          {visibleBuckets.map((b) => (
            <option key={b.name} value={b.name}>
              {b.name}
            </option>
          ))}
        </select>
        <select
          value={sortMode}
          onChange={(e) => setSortMode(e.target.value as "updated" | "created" | "alpha")}
          className="px-3 py-2.5 bg-[#2B2930] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
        >
          <option value="updated">Updated</option>
          <option value="created">Created</option>
          <option value="alpha">A–Z</option>
        </select>
      </div>

      {/* Notes list */}
      {loading ? (
        <div className="flex items-center gap-3 py-12 justify-center text-[#938F99]">
          <svg
            className="w-5 h-5 animate-spin"
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
          Loading notes...
        </div>
      ) : error ? (
        <div className="text-center py-12">
          <p className="text-[#F2B8B5] mb-3">{error}</p>
          <button
            onClick={fetchNotes}
            className="px-4 py-2 bg-[#6750A4] text-white rounded-lg hover:bg-[#7965AF] transition-colors"
          >
            Retry
          </button>
        </div>
      ) : sortedNotes.length === 0 ? (
        <div className="text-center py-16">
          <div className="w-16 h-16 rounded-2xl bg-[#2B2930] flex items-center justify-center mx-auto mb-4">
            <svg
              className="w-8 h-8 text-[#49454F]"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
              />
            </svg>
          </div>
          <p className="text-[#938F99]">
            {searchQuery || selectedBucket !== "All"
              ? "No notes match your search"
              : "No notes yet. Create your first note!"}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
          {sortedNotes.map((note) => (
            <button
              key={note.id}
              onClick={() => openNote(note)}
              className="text-left bg-[#2B2930] border border-[#49454F] rounded-2xl p-4 hover:border-[#6750A4]/50 hover:bg-[#2B2930]/80 transition-all group"
            >
              <div className="flex items-start justify-between gap-2 mb-2">
                <h3 className="font-medium text-[#E6E1E5] group-hover:text-white truncate">
                  {note.title}
                </h3>
                <span className="text-xs text-[#938F99] bg-[#49454F]/40 px-2 py-0.5 rounded-full flex-shrink-0">
                  {note.bucket}
                </span>
              </div>
              <p className="text-sm text-[#938F99] line-clamp-3">
                {note.content || "Empty note"}
              </p>
              {note.updatedAt && (
                <p className="text-xs text-[#49454F] mt-2">
                  {new Date(note.updatedAt).toLocaleDateString("en-AU")}
                </p>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
