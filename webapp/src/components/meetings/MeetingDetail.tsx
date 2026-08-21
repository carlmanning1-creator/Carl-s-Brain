"use client";

import { useState, useCallback, useRef } from "react";
import type { Meeting } from "@/lib/types";
import { useVault } from "@/hooks/useVault";

interface MeetingDetailProps {
  meeting: Meeting;
  onUpdated: (meeting: Meeting) => void;
}

export default function MeetingDetail({ meeting, onUpdated }: MeetingDetailProps) {
  // The audio and share routes check the meeting's bucket against the vault, so they need to
  // know whether it is open — otherwise a vault meeting Carl is legitimately looking at would
  // refuse to play.
  const { isVaultOpen } = useVault();
  const vaultQuery = isVaultOpen ? "vault=open" : "";
  const [summaryOpen, setSummaryOpen] = useState(true);
  const [transcriptOpen, setTranscriptOpen] = useState(false);
  const [showFullTranscript, setShowFullTranscript] = useState(false);
  const [editingTranscript, setEditingTranscript] = useState(false);
  const [editedTranscript, setEditedTranscript] = useState(meeting.transcript);
  const [savingTranscript, setSavingTranscript] = useState(false);
  const [reprocessing, setReprocessing] = useState(false);
  const [reprocessError, setReprocessError] = useState<string | null>(null);
  const [creatingTodo, setCreatingTodo] = useState<number | null>(null);
  const [createdTodos, setCreatedTodos] = useState<Set<number>>(new Set());
  const [todoError, setTodoError] = useState<string | null>(null);
  const [audioUrl, setAudioUrl] = useState<string | null>(null);
  const [audioLoading, setAudioLoading] = useState(false);
  const [audioError, setAudioError] = useState<string | null>(null);
  const audioRef = useRef<HTMLAudioElement>(null);
  const [sharing, setSharing] = useState(false);
  const [shareConfirm, setShareConfirm] = useState<string | null>(null);

  const formattedDate = new Date(meeting.recordedAt).toLocaleString("en-AU", {
    weekday: "short", day: "numeric", month: "short", year: "numeric",
    hour: "2-digit", minute: "2-digit",
  });

  const transcriptLines = (editingTranscript ? editedTranscript : meeting.transcript)
    .split("\n").filter((l) => l.trim());
  const previewLines = transcriptLines.slice(-4);

  const handleLoadAudio = useCallback(async () => {
    if (audioUrl) {
      audioRef.current?.play();
      return;
    }
    setAudioLoading(true);
    setAudioError(null);
    try {
      const res = await fetch(
        `/api/drive/meetings/audio?folderId=${meeting.id}${vaultQuery ? `&${vaultQuery}` : ""}`
      );
      if (!res.ok) {
        const d = await res.json().catch(() => ({}));
        throw new Error(d.error ?? "No recording found");
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      setAudioUrl(url);
    } catch (err) {
      setAudioError(err instanceof Error ? err.message : "Failed to load audio");
    } finally {
      setAudioLoading(false);
    }
  }, [meeting.id, audioUrl, vaultQuery]);

  const handleSaveTranscript = useCallback(async () => {
    setSavingTranscript(true);
    setReprocessError(null);
    try {
      const res = await fetch("/api/drive/meetings", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ folderId: meeting.id, transcript: editedTranscript }),
      });
      if (!res.ok) throw new Error("Failed to save transcript");
      onUpdated({ ...meeting, transcript: editedTranscript });
      setEditingTranscript(false);
    } catch (err) {
      setReprocessError(err instanceof Error ? err.message : "Failed to save");
    } finally {
      setSavingTranscript(false);
    }
  }, [meeting, editedTranscript, onUpdated]);

  const handleReprocess = useCallback(async () => {
    const transcriptToUse = editingTranscript ? editedTranscript : meeting.transcript;
    if (!transcriptToUse.trim()) {
      setReprocessError("Add a transcript first, then re-process.");
      return;
    }
    setReprocessing(true);
    setReprocessError(null);
    try {
      const processRes = await fetch("/api/meetings/process", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ transcript: transcriptToUse }),
      });
      if (!processRes.ok) {
        const d = await processRes.json().catch(() => ({}));
        throw new Error(d.error ?? "Process failed");
      }
      const { title, summary, actionItems } = await processRes.json();

      // Save updated files back to Drive
      await fetch("/api/drive/meetings", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          folderId: meeting.id,
          transcript: transcriptToUse,
          title,
          summary,
          actionItems,
        }),
      });

      onUpdated({ ...meeting, transcript: transcriptToUse, title, summary, actionItems, status: "DONE" });
      setEditingTranscript(false);
      setEditedTranscript(transcriptToUse);
    } catch (err) {
      setReprocessError(err instanceof Error ? err.message : "Failed to reprocess");
    } finally {
      setReprocessing(false);
    }
  }, [meeting, editingTranscript, editedTranscript, onUpdated]);

  const handleCreateTodo = useCallback(async (index: number) => {
    const item = meeting.actionItems[index];
    if (!item) return;
    setCreatingTodo(index);
    setTodoError(null);
    try {
      const todo = {
        id: Date.now(), title: item.title, bucket: item.bucket,
        priority: "NORMAL" as const, isDone: false, dueDate: null,
        createdAt: Date.now(), updatedAt: Date.now(), deletedAt: null,
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
  }, [meeting.actionItems]);

  const handleShareDriveLink = useCallback(async () => {
    setSharing(true);
    setShareConfirm(null);
    try {
      // Look up the summary.md file ID within the meeting folder
      const lookupRes = await fetch(`/api/drive/share?folderId=${meeting.id}${vaultQuery ? `&${vaultQuery}` : ""}`);
      if (!lookupRes.ok) {
        const d = await lookupRes.json().catch(() => ({}));
        throw new Error(d.error ?? "Could not find summary file");
      }
      const { fileId } = await lookupRes.json();

      // Make it publicly readable and get the link
      const shareRes = await fetch(`/api/drive/share${vaultQuery ? `?${vaultQuery}` : ""}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ fileId }),
      });
      if (!shareRes.ok) {
        const d = await shareRes.json().catch(() => ({}));
        throw new Error(d.error ?? "Failed to share");
      }
      const { webViewLink } = await shareRes.json();
      await navigator.clipboard.writeText(webViewLink);
      setShareConfirm("Link copied!");
      setTimeout(() => setShareConfirm(null), 3000);
    } catch (err) {
      setShareConfirm(err instanceof Error ? err.message : "Share failed");
      setTimeout(() => setShareConfirm(null), 4000);
    } finally {
      setSharing(false);
    }
  }, [meeting.id]);

  return (
    <div className="flex flex-col gap-0 h-full">
      {/* Header */}
      <div className="p-6 border-b border-[#49454F]">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 className="text-xl font-bold text-[#E6E1E5] leading-tight">{meeting.title}</h2>
            <p className="text-sm text-[#938F99] mt-1">{formattedDate}</p>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0 flex-wrap justify-end">
            <span className={`text-xs px-2 py-1 rounded-lg border ${
              meeting.status === "DONE"
                ? "text-emerald-400 bg-emerald-400/10 border-emerald-400/20"
                : meeting.status === "NO_TRANSCRIPT"
                ? "text-orange-400 bg-orange-400/10 border-orange-400/20"
                : "text-yellow-400 bg-yellow-400/10 border-yellow-400/20"
            }`}>
              {meeting.status === "DONE" ? "Processed" : meeting.status === "NO_TRANSCRIPT" ? "No Transcript" : "Audio Only"}
            </span>

            {/* Play recording button */}
            <button
              onClick={handleLoadAudio}
              disabled={audioLoading}
              title="Play recording from Drive"
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-[#CAC4D0] border border-[#49454F] rounded-lg hover:border-[#6750A4] hover:text-[#E6E1E5] disabled:opacity-40 transition-colors"
            >
              {audioLoading ? (
                <svg className="w-3.5 h-3.5 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
                </svg>
              ) : (
                <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"/>
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/>
                </svg>
              )}
              Play
            </button>

            {/* Edit transcript toggle */}
            <button
              onClick={() => { setEditingTranscript((v) => !v); setEditedTranscript(meeting.transcript); }}
              title={editingTranscript ? "Cancel editing" : "Edit or add transcript"}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-xs border rounded-lg transition-colors ${
                editingTranscript
                  ? "text-[#D0BCFF] border-[#6750A4] bg-[#6750A4]/10"
                  : "text-[#CAC4D0] border-[#49454F] hover:border-[#6750A4] hover:text-[#E6E1E5]"
              }`}
            >
              <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"/>
              </svg>
              {editingTranscript ? "Cancel" : "Edit Transcript"}
            </button>

            {/* Re-process button */}
            <button
              onClick={handleReprocess}
              disabled={reprocessing}
              title="Re-run Claude on this transcript"
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-[#CAC4D0] border border-[#49454F] rounded-lg hover:border-[#6750A4] hover:text-[#E6E1E5] disabled:opacity-40 transition-colors"
            >
              {reprocessing ? (
                <svg className="w-3.5 h-3.5 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
                </svg>
              ) : (
                <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/>
                </svg>
              )}
              Re-process
            </button>

            {/* Share Drive link button */}
            {meeting.status === "DONE" && (
              <button
                onClick={handleShareDriveLink}
                disabled={sharing}
                title="Share summary as Google Drive link"
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-[#CAC4D0] border border-[#49454F] rounded-lg hover:border-[#6750A4] hover:text-[#E6E1E5] disabled:opacity-40 transition-colors"
              >
                {sharing ? (
                  <svg className="w-3.5 h-3.5 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
                  </svg>
                ) : (
                  <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"/>
                  </svg>
                )}
                {shareConfirm ?? "Drive link"}
              </button>
            )}
          </div>
        </div>

        {/* Audio player */}
        {audioUrl && (
          <audio ref={audioRef} controls src={audioUrl} autoPlay className="w-full mt-3 h-10 rounded-lg" />
        )}
        {audioError && <p className="mt-2 text-xs text-yellow-400">{audioError} — no recording stored in Drive for this meeting</p>}
        {reprocessError && <p className="mt-2 text-xs text-[#F2B8B5]">{reprocessError}</p>}
      </div>

      <div className="flex-1 overflow-y-auto divide-y divide-[#49454F]">

        {/* Edit transcript panel */}
        {editingTranscript && (
          <div className="px-6 py-5 space-y-3">
            <p className="text-xs text-[#938F99]">
              Type or paste the transcript below, then hit <strong className="text-[#CAC4D0]">Save</strong> to update Drive or <strong className="text-[#CAC4D0]">Re-process</strong> to regenerate summary and action items. While the audio plays above, use it to transcribe manually.
            </p>
            <textarea
              value={editedTranscript}
              onChange={(e) => setEditedTranscript(e.target.value)}
              placeholder="Paste or type the meeting transcript here…"
              rows={10}
              className="w-full bg-[#1C1B1F] border border-[#49454F] focus:border-[#6750A4] rounded-xl px-4 py-3 text-sm text-[#E6E1E5] placeholder-[#938F99] resize-y outline-none font-mono"
            />
            <div className="flex gap-2">
              <button
                onClick={handleSaveTranscript}
                disabled={savingTranscript || editedTranscript === meeting.transcript}
                className="px-4 py-2 text-sm bg-[#6750A4] hover:bg-[#7965AF] text-white rounded-xl disabled:opacity-40 transition-colors"
              >
                {savingTranscript ? "Saving…" : "Save Transcript"}
              </button>
              <button
                onClick={handleReprocess}
                disabled={reprocessing || !editedTranscript.trim()}
                className="px-4 py-2 text-sm border border-[#6750A4] text-[#D0BCFF] hover:bg-[#6750A4]/10 rounded-xl disabled:opacity-40 transition-colors"
              >
                {reprocessing ? "Processing…" : "Save & Re-process with Claude"}
              </button>
            </div>
          </div>
        )}

        {/* Summary section */}
        {meeting.summary && (
          <div>
            <button
              onClick={() => setSummaryOpen((v) => !v)}
              className="w-full flex items-center justify-between px-6 py-4 text-left hover:bg-[#49454F]/20 transition-colors"
            >
              <span className="text-sm font-semibold text-[#CAC4D0] uppercase tracking-wider">Summary</span>
              <svg className={`w-4 h-4 text-[#938F99] transition-transform ${summaryOpen ? "rotate-180" : ""}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7"/>
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
            <h3 className="text-sm font-semibold text-[#CAC4D0] uppercase tracking-wider mb-3">Action Items</h3>
            {todoError && <p className="mb-2 text-xs text-[#F2B8B5]">{todoError}</p>}
            <ul className="space-y-2">
              {meeting.actionItems.map((item, i) => {
                const done = createdTodos.has(i);
                return (
                  <li key={i} className={`flex items-start gap-3 p-3 rounded-xl border transition-colors ${
                    done ? "border-emerald-400/20 bg-emerald-400/5" : "border-[#49454F] bg-[#1C1B1F] hover:border-[#6750A4]/30"
                  }`}>
                    <button
                      onClick={() => !done && handleCreateTodo(i)}
                      disabled={done || creatingTodo === i}
                      title={done ? "Already added to todos" : "Add to todos"}
                      className={`mt-0.5 w-5 h-5 rounded-full border-2 flex-shrink-0 flex items-center justify-center transition-colors ${
                        done ? "bg-emerald-500 border-emerald-500" : "border-[#49454F] hover:border-[#6750A4]"
                      }`}
                    >
                      {done ? (
                        <svg className="w-3 h-3 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7"/>
                        </svg>
                      ) : creatingTodo === i ? (
                        <svg className="w-3 h-3 text-[#6750A4] animate-spin" fill="none" viewBox="0 0 24 24">
                          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
                          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
                        </svg>
                      ) : null}
                    </button>
                    <div className="flex-1 min-w-0">
                      <p className={`text-sm ${done ? "text-[#938F99]" : "text-[#E6E1E5]"}`}>{item.title}</p>
                    </div>
                    <span className="text-xs text-[#6750A4] bg-[#6750A4]/10 px-1.5 py-0.5 rounded flex-shrink-0">{item.bucket}</span>
                  </li>
                );
              })}
            </ul>
            <p className="text-xs text-[#938F99] mt-2">Tick to add as a todo</p>
          </div>
        )}

        {/* Transcript section */}
        {meeting.transcript && !editingTranscript && (
          <div>
            <button
              onClick={() => setTranscriptOpen((v) => !v)}
              className="w-full flex items-center justify-between px-6 py-4 text-left hover:bg-[#49454F]/20 transition-colors"
            >
              <span className="text-sm font-semibold text-[#CAC4D0] uppercase tracking-wider">Transcript</span>
              <svg className={`w-4 h-4 text-[#938F99] transition-transform ${transcriptOpen ? "rotate-180" : ""}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7"/>
              </svg>
            </button>
            {transcriptOpen && (
              <div className="px-6 pb-5 space-y-3">
                <div className="text-sm text-[#CAC4D0] leading-relaxed whitespace-pre-wrap font-mono text-xs bg-[#1C1B1F] rounded-xl p-4 border border-[#49454F] max-h-[320px] overflow-y-auto">
                  {showFullTranscript ? meeting.transcript : previewLines.join("\n")}
                </div>
                {transcriptLines.length > 4 && (
                  <button onClick={() => setShowFullTranscript((v) => !v)} className="text-sm text-[#6750A4] hover:text-[#D0BCFF] transition-colors">
                    {showFullTranscript ? "Show less" : `Show all (${transcriptLines.length} lines)`}
                  </button>
                )}
              </div>
            )}
          </div>
        )}

        {/* Empty state for missing transcript */}
        {!meeting.transcript && !editingTranscript && (
          <div className="px-6 py-8 text-center">
            <p className="text-sm text-[#938F99] mb-3">No transcript available for this meeting.</p>
            <button
              onClick={() => { setEditingTranscript(true); setEditedTranscript(""); }}
              className="px-4 py-2 text-sm border border-[#6750A4] text-[#D0BCFF] hover:bg-[#6750A4]/10 rounded-xl transition-colors"
            >
              Add Transcript Manually
            </button>
            <p className="text-xs text-[#938F99] mt-2">Use Play to listen to the recording while you type</p>
          </div>
        )}
      </div>
    </div>
  );
}
