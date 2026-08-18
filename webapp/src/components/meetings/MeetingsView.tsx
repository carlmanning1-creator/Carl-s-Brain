"use client";

import { useState, useCallback, useEffect } from "react";
import type { Meeting } from "@/lib/types";
import MeetingDetail from "./MeetingDetail";
import MeetingRecorder from "./MeetingRecorder";
import { useVault } from "@/hooks/useVault";

export default function MeetingsView() {
  // Meetings previously had no vault handling at all: a meeting filed to a vault bucket was
  // listed here with its transcript like any other. The server now filters on this flag.
  const { isVaultOpen } = useVault();
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [hiddenVaultCount, setHiddenVaultCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [showRecorder, setShowRecorder] = useState(false);

  const fetchMeetings = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(
        `/api/drive/meetings${isVaultOpen ? "?vault=open" : ""}`
      );
      if (!res.ok) throw new Error("Failed to fetch meetings");
      const data = await res.json();
      setMeetings(data.meetings ?? []);
      setHiddenVaultCount(data.hiddenCount ?? 0);
    } catch {
      setError("Failed to load meetings.");
    } finally {
      setLoading(false);
    }
  }, [isVaultOpen]);

  useEffect(() => {
    fetchMeetings();
  }, [fetchMeetings]);

  const selectedMeeting = meetings.find((m) => m.id === selectedId) ?? null;

  const handleSaved = useCallback((meeting: Meeting) => {
    setMeetings((prev) => [meeting, ...prev]);
    setSelectedId(meeting.id);
    setShowRecorder(false);
  }, []);

  const handleUpdated = useCallback((updated: Meeting) => {
    setMeetings((prev) => prev.map((m) => (m.id === updated.id ? updated : m)));
  }, []);

  function formatRelativeDate(ts: number): string {
    const now = Date.now();
    const diff = now - ts;
    const days = Math.floor(diff / 86400000);
    if (days === 0) return "Today";
    if (days === 1) return "Yesterday";
    if (days < 7) return `${days} days ago`;
    return new Date(ts).toLocaleDateString("en-AU", {
      day: "numeric",
      month: "short",
      year: days > 365 ? "numeric" : undefined,
    });
  }

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Left panel — meeting list */}
      <div className="w-80 flex-shrink-0 border-r border-[#49454F] flex flex-col bg-[#2B2930]">
        {/* Header */}
        <div className="px-4 py-5 border-b border-[#49454F]">
          <div className="flex items-center justify-between mb-1">
            <h1 className="text-xl font-bold text-[#E6E1E5]">Meetings</h1>
            <div className="flex items-center gap-1.5">
              <button
                onClick={fetchMeetings}
                disabled={loading}
                className="p-2 text-[#938F99] hover:text-[#E6E1E5] disabled:opacity-40 transition-colors"
                title="Refresh"
              >
                <svg className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                </svg>
              </button>
              <button
                onClick={() => { setShowRecorder(true); setSelectedId(null); }}
                className="flex items-center gap-1.5 px-3 py-2 bg-[#6750A4] text-white text-sm rounded-xl hover:bg-[#7965AF] transition-colors font-medium"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                </svg>
                New
              </button>
            </div>
          </div>
          {!loading && (
            <p className="text-xs text-[#938F99]">
              {meetings.length} meeting{meetings.length !== 1 ? "s" : ""}
            </p>
          )}
        </div>

        {/* Meeting list */}
        <div className="flex-1 overflow-y-auto py-2">
          {loading ? (
            <div className="flex items-center gap-2 justify-center py-12 text-[#938F99] text-sm">
              <svg className="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
              </svg>
              Loading…
            </div>
          ) : error ? (
            <div className="px-4 py-8 text-center">
              <p className="text-[#F2B8B5] text-sm mb-3">{error}</p>
              <button
                onClick={fetchMeetings}
                className="px-4 py-2 bg-[#6750A4] text-white text-sm rounded-lg hover:bg-[#7965AF] transition-colors"
              >
                Retry
              </button>
            </div>
          ) : meetings.length === 0 ? (
            <div className="px-4 py-12 text-center">
              <div className="w-12 h-12 rounded-2xl bg-[#1C1B1F] flex items-center justify-center mx-auto mb-3">
                <svg className="w-6 h-6 text-[#49454F]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
                </svg>
              </div>
              <p className="text-[#938F99] text-sm">No meetings yet.</p>
              <p className="text-[#49454F] text-xs mt-1">Tap New to record one.</p>
            </div>
          ) : (
            <ul className="px-2 space-y-1">
              {meetings.map((m) => {
                const isActive = m.id === selectedId && !showRecorder;
                return (
                  <li key={m.id}>
                    <button
                      onClick={() => { setSelectedId(m.id); setShowRecorder(false); }}
                      className={`w-full text-left px-3 py-3 rounded-xl transition-colors ${
                        isActive
                          ? "bg-[#6750A4]/20 border border-[#6750A4]/30"
                          : "hover:bg-[#49454F]/40 border border-transparent"
                      }`}
                    >
                      <p className={`text-sm font-medium truncate ${isActive ? "text-[#D0BCFF]" : "text-[#E6E1E5]"}`}>
                        {m.title}
                      </p>
                      <div className="flex items-center gap-2 mt-0.5">
                        <span className="text-xs text-[#938F99]">{formatRelativeDate(m.recordedAt)}</span>
                        {m.actionItems.length > 0 && (
                          <span className="text-xs text-[#6750A4] bg-[#6750A4]/10 px-1.5 py-0.5 rounded">
                            {m.actionItems.length} action{m.actionItems.length !== 1 ? "s" : ""}
                          </span>
                        )}
                      </div>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>

      {/* Right panel */}
      <div className="flex-1 overflow-hidden bg-[#1C1B1F]">
        {showRecorder ? (
          <div className="h-full overflow-y-auto">
            <MeetingRecorder
              onSaved={handleSaved}
              onCancel={() => setShowRecorder(false)}
            />
          </div>
        ) : selectedMeeting ? (
          <div className="h-full overflow-hidden flex flex-col">
            <MeetingDetail
              meeting={selectedMeeting}
              onUpdated={handleUpdated}
            />
          </div>
        ) : (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-center px-8">
            <div className="w-20 h-20 rounded-2xl bg-[#2B2930] flex items-center justify-center">
              <svg className="w-10 h-10 text-[#49454F]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
              </svg>
            </div>
            <div>
              <p className="text-[#CAC4D0] font-medium">Select a meeting</p>
              <p className="text-[#938F99] text-sm mt-1">or record a new one</p>
            </div>
            <button
              onClick={() => setShowRecorder(true)}
              className="px-5 py-2.5 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] transition-colors font-medium"
            >
              New Meeting
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
