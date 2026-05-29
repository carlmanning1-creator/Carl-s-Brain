"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import type { Meeting, ActionItem } from "@/lib/types";

interface ProcessResult {
  title: string;
  summary: string;
  actionItems: ActionItem[];
}

interface MeetingRecorderProps {
  onSaved: (meeting: Meeting) => void;
  onCancel: () => void;
}

// ─── SpeechRecognition type declarations ──────────────────────────────────────

interface SpeechRecognitionResultItem {
  transcript: string;
  confidence: number;
}

interface SpeechRecognitionResult {
  readonly isFinal: boolean;
  readonly length: number;
  item(index: number): SpeechRecognitionResultItem;
  [index: number]: SpeechRecognitionResultItem;
}

interface SpeechRecognitionResultList {
  readonly length: number;
  item(index: number): SpeechRecognitionResult;
  [index: number]: SpeechRecognitionResult;
}

interface SpeechRecognitionEvent extends Event {
  readonly resultIndex: number;
  readonly results: SpeechRecognitionResultList;
}

interface SpeechRecognitionErrorEvent extends Event {
  readonly error: string;
  readonly message: string;
}

interface SpeechRecognitionInstance extends EventTarget {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((event: SpeechRecognitionEvent) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEvent) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}

interface SpeechRecognitionConstructor {
  new (): SpeechRecognitionInstance;
}

declare global {
  interface Window {
    SpeechRecognition: SpeechRecognitionConstructor;
    webkitSpeechRecognition: SpeechRecognitionConstructor;
  }
}

export default function MeetingRecorder({ onSaved, onCancel }: MeetingRecorderProps) {
  const [isRecording, setIsRecording] = useState(false);
  const [liveTranscript, setLiveTranscript] = useState("");
  const [finalTranscript, setFinalTranscript] = useState("");
  const [processResult, setProcessResult] = useState<ProcessResult | null>(null);
  const [hasSpeechSupport, setHasSpeechSupport] = useState(true);
  const [manualTranscript, setManualTranscript] = useState("");
  const [processing, setProcessing] = useState(false);
  const [isTranscribing, setIsTranscribing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recordingDuration, setRecordingDuration] = useState(0);

  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recognitionRef = useRef<SpeechRecognitionInstance | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);
  const durationTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const recordingStartRef = useRef<number>(0);

  useEffect(() => {
    const SpeechRecognitionClass =
      typeof window !== "undefined"
        ? window.SpeechRecognition ?? window.webkitSpeechRecognition
        : null;
    if (!SpeechRecognitionClass) {
      setHasSpeechSupport(false);
    }
    return () => {
      stopRecognition();
      if (durationTimerRef.current) clearInterval(durationTimerRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stopRecognition = useCallback(() => {
    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch {
        // ignore
      }
      recognitionRef.current = null;
    }
  }, []);

  const startRecording = useCallback(async () => {
    setError(null);
    setLiveTranscript("");
    setFinalTranscript("");
    setProcessResult(null);
    audioChunksRef.current = [];
    recordingStartRef.current = Date.now();
    setRecordingDuration(0);

    // Start MediaRecorder for audio capture
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mimeType = MediaRecorder.isTypeSupported("audio/webm;codecs=opus")
        ? "audio/webm;codecs=opus"
        : MediaRecorder.isTypeSupported("audio/webm")
        ? "audio/webm"
        : "";
      const mr = mimeType
        ? new MediaRecorder(stream, { mimeType })
        : new MediaRecorder(stream);
      mediaRecorderRef.current = mr;

      mr.ondataavailable = (e) => {
        if (e.data.size > 0) audioChunksRef.current.push(e.data);
      };

      mr.start(1000);
    } catch {
      // Mic not available — we'll still do text-only transcription
    }

    // Start SpeechRecognition for live text
    if (hasSpeechSupport) {
      const SpeechRecognitionClass =
        window.SpeechRecognition ?? window.webkitSpeechRecognition;
      const recognition = new SpeechRecognitionClass();
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = "en-AU";

      let accumulatedFinal = "";

      recognition.onresult = (event: SpeechRecognitionEvent) => {
        let interim = "";
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const result = event.results[i];
          if (result.isFinal) {
            accumulatedFinal += result[0].transcript + " ";
          } else {
            interim += result[0].transcript;
          }
        }
        setFinalTranscript(accumulatedFinal);
        setLiveTranscript(interim);
      };

      recognition.onerror = (event: SpeechRecognitionErrorEvent) => {
        // network errors are common — just log
        console.warn("Speech recognition error:", event.error);
      };

      recognition.onend = () => {
        // Restart if still recording (recognition cuts out after ~60s in Chrome)
        if (mediaRecorderRef.current?.state === "recording") {
          try {
            recognition.start();
          } catch {
            // ignore
          }
        }
      };

      recognitionRef.current = recognition;
      recognition.start();
    }

    // Timer
    durationTimerRef.current = setInterval(() => {
      setRecordingDuration(Math.floor((Date.now() - recordingStartRef.current) / 1000));
    }, 1000);

    setIsRecording(true);
  }, [hasSpeechSupport]);

  const stopRecording = useCallback(() => {
    setIsRecording(false);

    if (durationTimerRef.current) {
      clearInterval(durationTimerRef.current);
      durationTimerRef.current = null;
    }

    stopRecognition();

    if (mediaRecorderRef.current && mediaRecorderRef.current.state !== "inactive") {
      mediaRecorderRef.current.stop();
      mediaRecorderRef.current.stream.getTracks().forEach((t) => t.stop());
    }

    // Combine live + final transcript
    setFinalTranscript((prev) => {
      const combined = (prev + " " + liveTranscript).trim();
      setLiveTranscript("");
      return combined;
    });
  }, [stopRecognition, liveTranscript]);

  const currentTranscript = hasSpeechSupport
    ? (finalTranscript + (liveTranscript ? " " + liveTranscript : "")).trim()
    : manualTranscript.trim();

  const handleProcess = useCallback(async () => {
    const webSpeechText = currentTranscript;
    if (!webSpeechText && audioChunksRef.current.length === 0) {
      setError("No transcript to process.");
      return;
    }
    setError(null);

    // Try Whisper transcription if audio was recorded
    let finalText = webSpeechText;
    if (audioChunksRef.current.length > 0) {
      try {
        setIsTranscribing(true);
        const audioBlob = new Blob(audioChunksRef.current, { type: "audio/webm" });
        const form = new FormData();
        form.append("audio", audioBlob, "recording.webm");
        const res = await fetch("/api/meetings/transcribe", { method: "POST", body: form });
        if (res.ok) {
          const data = await res.json();
          if (data.transcript) finalText = data.transcript;
        }
      } catch {
        // ignore — fall back to live transcript
      } finally {
        setIsTranscribing(false);
      }
    }

    if (!finalText) {
      setError("No transcript to process.");
      return;
    }

    setProcessing(true);
    try {
      const res = await fetch("/api/meetings/process", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ transcript: finalText }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error ?? "Process failed");
      }
      const data: ProcessResult = await res.json();
      setProcessResult(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to process transcript");
    } finally {
      setProcessing(false);
    }
  }, [currentTranscript]);

  const handleSave = useCallback(async () => {
    if (!processResult) return;
    setSaving(true);
    setError(null);
    try {
      const res = await fetch("/api/drive/meetings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          title: processResult.title,
          transcript: currentTranscript,
          summary: processResult.summary,
          actionItems: processResult.actionItems,
        }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error ?? "Save failed");
      }
      const data = await res.json();

      // Optionally upload audio blob in background
      if (audioChunksRef.current.length > 0) {
        const audioBlob = new Blob(audioChunksRef.current, { type: "audio/webm" });
        const fd = new FormData();
        fd.append("audio", audioBlob, "recording.webm");
        fd.append("meetingId", data.meeting.id);
        fetch("/api/meetings/audio", { method: "POST", body: fd }).catch(
          () => {} // non-critical
        );
      }

      onSaved(data.meeting as Meeting);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save meeting");
    } finally {
      setSaving(false);
    }
  }, [processResult, currentTranscript, onSaved]);

  function formatDuration(secs: number) {
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m}:${s.toString().padStart(2, "0")}`;
  }

  return (
    <div className="flex flex-col gap-6 p-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-[#E6E1E5]">New Meeting</h2>
        <button
          onClick={onCancel}
          className="text-[#938F99] hover:text-[#CAC4D0] transition-colors"
          aria-label="Cancel"
        >
          <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      {/* Recording controls */}
      <div className="flex flex-col items-center gap-4 py-4">
        {isRecording ? (
          <div className="flex flex-col items-center gap-3">
            <div className="relative">
              <div className="w-16 h-16 rounded-full bg-red-500/20 flex items-center justify-center animate-pulse">
                <div className="w-10 h-10 rounded-full bg-red-500 flex items-center justify-center">
                  <div className="w-4 h-4 rounded bg-white" />
                </div>
              </div>
            </div>
            <span className="text-sm font-mono text-red-400">{formatDuration(recordingDuration)}</span>
            <button
              onClick={stopRecording}
              className="px-6 py-2.5 bg-[#2B2930] border border-[#49454F] text-[#E6E1E5] rounded-xl hover:bg-[#49454F]/40 transition-colors font-medium"
            >
              Stop Recording
            </button>
          </div>
        ) : !finalTranscript && !manualTranscript ? (
          <button
            onClick={startRecording}
            className="flex flex-col items-center gap-3 group"
          >
            <div className="w-16 h-16 rounded-full bg-[#6750A4]/20 flex items-center justify-center group-hover:bg-[#6750A4]/30 transition-colors">
              <svg className="w-8 h-8 text-[#6750A4]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
              </svg>
            </div>
            <span className="text-sm text-[#938F99] group-hover:text-[#CAC4D0] transition-colors">
              Tap to start recording
            </span>
          </button>
        ) : null}
      </div>

      {/* Transcript area */}
      {hasSpeechSupport ? (
        <div className="space-y-2">
          <label className="text-sm font-medium text-[#CAC4D0]">
            {isRecording ? "Live Transcript" : "Transcript"}
          </label>
          <div className="min-h-[140px] max-h-[280px] overflow-y-auto p-4 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-sm text-[#E6E1E5] leading-relaxed whitespace-pre-wrap">
            {finalTranscript}
            {liveTranscript && (
              <span className="text-[#938F99] italic">{liveTranscript}</span>
            )}
            {!finalTranscript && !liveTranscript && (
              <span className="text-[#49454F]">
                {isRecording ? "Listening..." : "Transcript will appear here"}
              </span>
            )}
          </div>
          {(finalTranscript || isRecording) && !isRecording && (
            <div className="flex gap-2 justify-end">
              <button
                onClick={() => { setFinalTranscript(""); setProcessResult(null); }}
                className="px-3 py-1.5 text-sm text-[#938F99] hover:text-[#CAC4D0] transition-colors"
              >
                Clear
              </button>
              <button
                onClick={startRecording}
                className="px-3 py-1.5 text-sm text-[#CAC4D0] hover:text-[#E6E1E5] border border-[#49454F] rounded-lg hover:border-[#6750A4] transition-colors"
              >
                Re-record
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="space-y-2">
          <label className="text-sm font-medium text-[#CAC4D0]">
            Transcript <span className="text-[#938F99] font-normal">(browser speech unavailable — paste manually)</span>
          </label>
          <textarea
            value={manualTranscript}
            onChange={(e) => { setManualTranscript(e.target.value); setProcessResult(null); }}
            rows={8}
            placeholder="Paste or type your meeting transcript here…"
            className="w-full p-4 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-sm text-[#E6E1E5] placeholder-[#49454F] focus:outline-none focus:border-[#6750A4] resize-none leading-relaxed"
          />
        </div>
      )}

      {/* Process result */}
      {processResult && (
        <div className="space-y-4 p-4 bg-[#1C1B1F] border border-[#6750A4]/30 rounded-xl">
          <div>
            <p className="text-xs font-medium text-[#938F99] uppercase tracking-wider mb-1">Title</p>
            <p className="text-[#E6E1E5] font-semibold">{processResult.title}</p>
          </div>
          <div>
            <p className="text-xs font-medium text-[#938F99] uppercase tracking-wider mb-1">Summary</p>
            <p className="text-sm text-[#CAC4D0] leading-relaxed">{processResult.summary}</p>
          </div>
          {processResult.actionItems.length > 0 && (
            <div>
              <p className="text-xs font-medium text-[#938F99] uppercase tracking-wider mb-2">Action Items</p>
              <ul className="space-y-1.5">
                {processResult.actionItems.map((item, i) => (
                  <li key={i} className="flex items-start gap-2 text-sm">
                    <span className="mt-0.5 w-1.5 h-1.5 rounded-full bg-[#6750A4] flex-shrink-0" />
                    <span className="text-[#E6E1E5]">{item.title}</span>
                    <span className="ml-auto text-xs text-[#6750A4] bg-[#6750A4]/10 px-1.5 py-0.5 rounded flex-shrink-0">
                      {item.bucket}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {isTranscribing && (
        <div className="flex items-center gap-2 text-sm text-[#CAC4D0]">
          <svg className="w-4 h-4 animate-spin flex-shrink-0" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
          Transcribing audio…
        </div>
      )}

      {error && (
        <p className="text-sm text-[#F2B8B5] bg-red-400/10 border border-red-400/20 rounded-lg px-4 py-2">
          {error}
        </p>
      )}

      {/* Action buttons */}
      <div className="flex items-center gap-3 pt-2">
        {!isRecording && (currentTranscript || audioChunksRef.current.length > 0) && !processResult && (
          <button
            onClick={handleProcess}
            disabled={processing || isTranscribing}
            className="flex items-center gap-2 px-5 py-2.5 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium"
          >
            {processing ? (
              <>
                <svg className="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                Processing…
              </>
            ) : (
              <>
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
                Process with Claude
              </>
            )}
          </button>
        )}

        {processResult && (
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex items-center gap-2 px-5 py-2.5 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] disabled:opacity-50 disabled:cursor-not-allowed transition-colors font-medium"
          >
            {saving ? (
              <>
                <svg className="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                Saving…
              </>
            ) : (
              <>
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" />
                </svg>
                Save Meeting
              </>
            )}
          </button>
        )}

        {processResult && (
          <button
            onClick={handleProcess}
            disabled={processing || isTranscribing}
            className="px-4 py-2.5 text-sm text-[#CAC4D0] border border-[#49454F] rounded-xl hover:border-[#6750A4] hover:text-[#E6E1E5] disabled:opacity-50 transition-colors"
          >
            Re-process
          </button>
        )}

        <button
          onClick={onCancel}
          className="px-4 py-2.5 text-sm text-[#938F99] hover:text-[#CAC4D0] transition-colors ml-auto"
        >
          Cancel
        </button>
      </div>
    </div>
  );
}
