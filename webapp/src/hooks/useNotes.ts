"use client";

import { useState, useCallback, useRef, useEffect } from "react";
import type { NoteDto } from "@/lib/types";

/**
 * @param isVaultOpen passed through to the server, which decides what to send. The client
 *   never receives vault notes while locked, so there is nothing here to filter.
 */
export function useNotes(isVaultOpen = false) {
  const [notes, setNotes] = useState<NoteDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** Count of notes the server withheld — a number only, never the notes themselves. */
  const [hiddenVaultCount, setHiddenVaultCount] = useState(0);

  /**
   * Mirror of [notes] for callbacks that must not re-create themselves when the list changes.
   * saveNote needs the current attachments for the note being saved, but taking `notes` as a
   * dependency would give every consumer a new function identity on every fetch.
   */
  const notesRef = useRef<NoteDto[]>([]);
  useEffect(() => {
    notesRef.current = notes;
  }, [notes]);

  const fetchNotes = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(
        `/api/drive/notes${isVaultOpen ? "?vault=open" : ""}`
      );
      if (!res.ok) throw new Error("Failed to fetch notes");
      const data = await res.json();
      setNotes(data.notes ?? []);
      setHiddenVaultCount(data.hiddenCount ?? 0);
    } catch (err) {
      setError("Failed to load notes.");
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [isVaultOpen]);

  const saveNote = useCallback(
    async (
      id: string,
      title: string,
      content: string,
      bucket: string
    ): Promise<boolean> => {
      try {
        // Read from the copy we already loaded rather than asking the caller for it: the
        // editor has no attachment UI and would have nothing to pass, and a save that omits
        // this rewrites the file without the comment — orphaning the note's photos in Drive.
        //
        // Read through the ref so this callback keeps a stable identity while still seeing
        // the current list.
        const attachments =
          notesRef.current.find((n) => n.id === id)?.attachments ?? "";
        const res = await fetch("/api/drive/notes", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ id, title, content, bucket, attachments }),
        });
        if (!res.ok) throw new Error("Failed to save note");

        // Optimistic update
        setNotes((prev) => {
          const existing = prev.findIndex((n) => n.id === id);
          const updated: NoteDto = {
            id,
            title,
            content,
            bucket,
            attachments,
            updatedAt: Date.now(),
          };
          if (existing >= 0) {
            const next = [...prev];
            next[existing] = { ...prev[existing], ...updated };
            return next;
          }
          return [{ ...updated, createdAt: Date.now() }, ...prev];
        });
        return true;
      } catch (err) {
        console.error(err);
        return false;
      }
    },
    []
  );

  const deleteNote = useCallback(async (id: string): Promise<boolean> => {
    try {
      const res = await fetch(`/api/drive/notes?id=${encodeURIComponent(id)}`, {
        method: "DELETE",
      });
      if (!res.ok) throw new Error("Failed to delete note");
      setNotes((prev) => prev.filter((n) => n.id !== id));
      return true;
    } catch (err) {
      console.error(err);
      return false;
    }
  }, []);

  return {
    notes,
    loading,
    error,
    hiddenVaultCount,
    fetchNotes,
    saveNote,
    deleteNote,
  };
}
