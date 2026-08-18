"use client";

import { useState, useCallback } from "react";
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
        const res = await fetch("/api/drive/notes", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ id, title, content, bucket }),
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
