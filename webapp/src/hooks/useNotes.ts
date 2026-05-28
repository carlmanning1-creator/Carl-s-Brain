"use client";

import { useState, useCallback } from "react";
import type { NoteDto } from "@/lib/types";

export function useNotes() {
  const [notes, setNotes] = useState<NoteDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchNotes = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/drive/notes");
      if (!res.ok) throw new Error("Failed to fetch notes");
      const data = await res.json();
      setNotes(data.notes ?? []);
    } catch (err) {
      setError("Failed to load notes.");
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, []);

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

  return { notes, loading, error, fetchNotes, saveNote, deleteNote };
}
