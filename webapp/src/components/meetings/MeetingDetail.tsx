"use client";

import { useState, useCallback } from "react";
import type { Meeting } from "@/lib/types";

interface MeetingDetailProps {
  meeting: Meeting;
  onUpdated: (meeting: Meeting) => void;
}

export default function MeetingDetail({ meeting, onUpdated }: MeetingDetailProps) {
  const [summaryOpen, setSummaryOpen] = useState(true);
  const [transcriptOpen, setTranscriptOpen] = useState(false);
  const [showFullTranscript, setShowFullTranscript] = useState(false);
  const [reprocessing, setReprocessing] = useState(false);
  const [reprocessError, setReprocessError] = useState<string | null>(null);
  const [creatingTodo, setCreatingTodo] = useState<number | null>(null);
  const [createdTodos, setCreatedTodos] = useState<Set<number>>(new Set());
  const [todoError, setTodoError] = useState<string | null>(null);

  const formattedDate = new Date(meeting.recordedAt).toLocaleString("en-AU", {
    weekday: "short",
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });

  const transcriptLines = meeting.transcript.split("\n").filter((l) => l.trim());
  const previewLines = transcriptLines.slice(-4);

  const handleReprocess = useCallback(async () => {
    if (!meeting.transcript) {
      setReprocessError("No transcript to reprocess.");
      return;
    }
    setReprocessing(true);
    setReprocessError(null);
    try {
      const processRes = await fetch("/api/meetings/process", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ transcript: meeting.transcript }),
      });
      if (!processRes.ok) {
        const d = await processRes.json().catch(() => ({}));
        throw new Error(d.error ?? "Process failed");
      }
      const { title, summary, actionItems } = await processRes.json();

      // Save updated meeting back to Drive via POST (creates a new dated folder — for simplicity)
      // For re-process we just update the in-memory meeting and show result
      // A full re-save would need a PATCH/PUT; for now reflect result locally
      onUpdated({ ...meeting, title, summary, actionItems, status: "DONE" });
    } catch (err) {
      setReprocessError(err instanceof Error ? err.message : "Failed to reprocess");
    } finally {
      setReprocessing(false);
    }
  }, [meeting, onUpdated]);

  const handleCreateTodo = useCallback(
    async (index: number) => {
      const item = meeting.actionItems[index];
      if (!item) return;
      setCreatingTodo(index);
      setTodoError(null);
      try {
        const todo = {
          id: Date.now(),
          title: item.title,
          bucket: item.bucket,
          priority: "NORMAL" as const,
          isDone: false,
          dueDate: null,
          createdAt: Date.now(),
          updatedAt: Date.now(),
          deletedAt: null,
        };
        const res = await fetch("/api/drive/todos", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ todo }),
        });
        if (!res.ok) {
          const d = await res.json().catch(() => ({}));
          throw new Error(d.error ?? "Failed to create todo");
        }
        setCreatedTodos((prev) => new Set([...prev, index]));
      } catch (err) {
        setTodoError(err instanceof Error ? err.message : "Failed to create todo");
      } finally {
        setCreatingTodo(null);
      }
    },
    [meeting.actionItems]
  );

  return (
    <div className="flex flex-col gap-0 h-full">
      {/* Header */}
      <div className="p-6 border-b border-[#49454F]">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-xl font-bold text-[#E6E1E5] leading-tight">{meeting.title}</h2>
            <p className="text-sm text-[#938F99] mt-1">{formattedDate}</p>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            <span
              className={`text-xs px-2 py-1 rounded-lg border ${
                meeting.status === "DONE"
                  ? "text-emerald-400 bg-emerald-400/10 border-emerald-400/20"
                  : meeting.status === "NO_TRANSCRIPT"
                  ? "text-orange-400 bg-orange-400/10 border-orange-400/20"
                  : "text-[#938F99] bg-[#938F99]/10 border-[#938F99]/20"
              }`}
            >
              {meeting.status === "DONE"
                ? "Processed"
                : meeting.status === "NO_TRANSCRIPT"
                ? "No Transcript"
                : "Audio Only"}
            </span>
            <button
              onClick={handleReprocess}
              disabled={reprocessing || !meeting.transcript}
              title="Re-run Claude on this transcript"
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-[#CAC4D0] border border-[#49454F] rounded-lg hover:border-[#6750A4] hover:text-[#E6E1E5] disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              {reprocessing ? (
                <svg className="w-3.5 h-3.5 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
              ) : (
                <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                </svg>
              )}
              Re-process
            </button>
          </div>
        </div>
        {reprocessError && (
          <p className="mt-2 text-xs text-[#F2B8B5]">{reprocessError}</p>
        )}
      </div>

      <div className="flex-1 overflow-y-auto divide-y divide-[#49454F]">
        {/* Summary section */}
        {meeting.summary && (
          <div>
            <button
              onClick={() => setSummaryOpen((v) => !v)}
              className="w-full flex items-center justify-between px-6 py-4 text-left hover:bg-[#49454F]/20 transition-colors"
            >
              <span className="text-sm font-semibold text-[#CAC4D0] uppercase tracking-wider">Summary</span>
              <svg
                className={`w-4 h-4 text-[#938F99] transition-transform ${summaryOpen ? "rotate-180" : ""}`}
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            {summaryOpen && (
              <div className="px-6 pb-5">
                <p className="text-sm text-[#CAC4D0] leading-relaxed whitespace-pre-wrap">{meeting.summary}</p>
              </div>
            )}
          </div>
        )}

        {/* Action items section */}
        {meeting.actionItems.length > 0 && (
          <div className="px-6 py-5">
            <h3 className="text-sm font-semibold text-[#CAC4D0] uppercase tracking-wider mb-3">
              Action Items
            </h3>
            {todoError && (
              <p className="mb-2 text-xs text-[#F2B8B5]">{todoError}</p>
            )}
            <ul className="space-y-2">
              {meeting.actionItems.map((item, i) => {
                const done = createdTodos.has(i);
                return (
                  <li
                    key={i}
                    className={`flex items-start gap-3 p-3 rounded-xl border transition-colors ${
                      done
                        ? "border-emerald-400/20 bg-emerald-400/5"
                        : "border-[#49454F] bg-[#1C1B1F] hover:border-[#6750A4]/30"
                    }`}
                  >
                    <button
                      onClick={() => !done && handleCreateTodo(i)}
                      disabled={done || creatingTodo === i}
                      title={done ? "Already added to todos" : "Add to todos"}
                      className={`mt-0.5 w-5 h-5 rounded-full border-2 flex-shrink-0 flex items-center justify-center transition-colors ${
                        done
                          ? "bg-emerald-500 border-emerald-500"
                          : "border-[#49454F] hover:border-[#6750A4]"
                      }`}
                      aria-label="Create todo"
                    >
                      {done ? (
                        <svg className="w-3 h-3 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
                        </svg>
                      ) : creatingTodo === i ? (
                        <svg className="w-3 h-3 text-[#6750A4] animate-spin" fill="none" viewBox="0 0 24 24">
                          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                        </svg>
                      ) : null}
                    </button>
                    <div className="flex-1 min-w-0">
                      <p className={`text-sm ${done ? "text-[#938F99]" : "text-[#E6E1E5]"}`}>{item.title}</p>
                    </div>
                    <span className="text-xs text-[#6750A4] bg-[#6750A4]/10 px-1.5 py-0.5 rounded flex-shrink-0">
                      {item.bucket}
                    </span>
                  </li>
                );
              })}
            </ul>
            <p className="text-xs text-[#938F99] mt-2">Tick to add as a todo</p>
          </div>
        )}

        {/* Transcript section */}
        {meeting.transcript && (
          <div>
            <button
              onClick={() => setTranscriptOpen((v) => !v)}
              className="w-full flex items-center justify-between px-6 py-4 text-left hover:bg-[#49454F]/20 transition-colors"
            >
              <span className="text-sm font-semibold text-[#CAC4D0] uppercase tracking-wider">Transcript</span>
              <svg
                className={`w-4 h-4 text-[#938F99] transition-transform ${transcriptOpen ? "rotate-180" : ""}`}
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            {transcriptOpen && (
              <div className="px-6 pb-5 space-y-3">
                <div className="text-sm text-[#CAC4D0] leading-relaxed whitespace-pre-wrap font-mono text-xs bg-[#1C1B1F] rounded-xl p-4 border border-[#49454F] max-h-[320px] overflow-y-auto">
                  {showFullTranscript
                    ? meeting.transcript
                    : previewLines.join("\n")}
                </div>
                {transcriptLines.length > 4 && (
                  <button
                    onClick={() => setShowFullTranscript((v) => !v)}
                    className="text-sm text-[#6750A4] hover:text-[#D0BCFF] transition-colors"
                  >
                    {showFullTranscript
                      ? "Show less"
                      : `Show all (${transcriptLines.length} lines)`}
                  </button>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
