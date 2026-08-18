"use client";

import { useState, useEffect, useCallback } from "react";
import { useVault } from "@/hooks/useVault";

export default function SettingsContent() {

  // Anthropic API key state
  const [hasApiKey, setHasApiKey] = useState(false);
  const [maskedKey, setMaskedKey] = useState<string | null>(null);
  const [newApiKey, setNewApiKey] = useState("");
  const [savingKey, setSavingKey] = useState(false);
  const [keyMessage, setKeyMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  // OpenAI API key state
  const [hasOpenaiKey, setHasOpenaiKey] = useState(false);
  const [maskedOpenaiKey, setMaskedOpenaiKey] = useState<string | null>(null);
  const [newOpenaiKey, setNewOpenaiKey] = useState("");
  const [savingOpenaiKey, setSavingOpenaiKey] = useState(false);
  const [openaiKeyMessage, setOpenaiKeyMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  // Memory state
  const [memory, setMemory] = useState("");
  const [loadingMemory, setLoadingMemory] = useState(true);
  const [savingMemory, setSavingMemory] = useState(false);
  const [memoryMessage, setMemoryMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);
  const [memoryDirty, setMemoryDirty] = useState(false);

  const fetchSettings = useCallback(async () => {
    try {
      const res = await fetch("/api/drive/settings");
      if (res.ok) {
        const data = await res.json();
        setHasApiKey(data.hasApiKey);
        setMaskedKey(data.maskedKey);
        setHasOpenaiKey(data.hasOpenaiKey ?? false);
        setMaskedOpenaiKey(data.maskedOpenaiKey ?? null);
      }
    } catch {
      // ignore
    }
  }, []);

  const fetchMemory = useCallback(async () => {
    setLoadingMemory(true);
    try {
      const res = await fetch("/api/drive/memory");
      if (res.ok) {
        const data = await res.json();
        setMemory(data.content);
      }
    } catch {
      // ignore
    } finally {
      setLoadingMemory(false);
    }
  }, []);

  useEffect(() => {
    fetchSettings();
    fetchMemory();
  }, [fetchSettings, fetchMemory]);

  async function saveApiKey(e: React.FormEvent) {
    e.preventDefault();
    if (!newApiKey.trim()) return;
    setSavingKey(true);
    setKeyMessage(null);
    try {
      const res = await fetch("/api/drive/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ apiKey: newApiKey.trim() }),
      });
      if (!res.ok) throw new Error("Save failed");
      setKeyMessage({ type: "success", text: "API key saved successfully." });
      setNewApiKey("");
      await fetchSettings();
    } catch {
      setKeyMessage({ type: "error", text: "Failed to save API key." });
    } finally {
      setSavingKey(false);
    }
  }

  async function saveOpenaiApiKey(e: React.FormEvent) {
    e.preventDefault();
    if (!newOpenaiKey.trim()) return;
    setSavingOpenaiKey(true);
    setOpenaiKeyMessage(null);
    try {
      const res = await fetch("/api/drive/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ openaiApiKey: newOpenaiKey.trim() }),
      });
      if (!res.ok) throw new Error("Save failed");
      setOpenaiKeyMessage({ type: "success", text: "OpenAI API key saved successfully." });
      setNewOpenaiKey("");
      await fetchSettings();
    } catch {
      setOpenaiKeyMessage({ type: "error", text: "Failed to save OpenAI API key." });
    } finally {
      setSavingOpenaiKey(false);
    }
  }

  async function saveMemory() {
    setSavingMemory(true);
    setMemoryMessage(null);
    try {
      const res = await fetch("/api/drive/memory", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ content: memory }),
      });
      if (!res.ok) throw new Error("Save failed");
      setMemoryMessage({ type: "success", text: "Memory saved." });
      setMemoryDirty(false);
    } catch {
      setMemoryMessage({ type: "error", text: "Failed to save memory." });
    } finally {
      setSavingMemory(false);
    }
  }

  return (
    <div className="max-w-2xl space-y-8">
      <h1 className="text-2xl font-bold text-[#E6E1E5]">Settings</h1>

      {/* API Key */}
      <section className="bg-[#2B2930] rounded-2xl border border-[#49454F] p-6">
        <h2 className="text-lg font-semibold text-[#E6E1E5] mb-1">
          Anthropic API Key
        </h2>
        <p className="text-sm text-[#938F99] mb-4">
          Required for AI features. Your key is stored encrypted in Google Drive,
          never on our servers.
        </p>

        {hasApiKey && maskedKey && (
          <div className="flex items-center gap-2 mb-4 p-3 bg-green-400/10 border border-green-400/20 rounded-xl">
            <svg
              className="w-4 h-4 text-green-400 flex-shrink-0"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M5 13l4 4L19 7"
              />
            </svg>
            <span className="text-sm text-green-400">
              API key configured: <code className="font-mono">{maskedKey}</code>
            </span>
          </div>
        )}

        <form onSubmit={saveApiKey} className="flex gap-3">
          <input
            type="password"
            value={newApiKey}
            onChange={(e) => setNewApiKey(e.target.value)}
            placeholder={hasApiKey ? "Enter new API key to update" : "sk-ant-..."}
            className="flex-1 px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4] font-mono text-sm"
          />
          <button
            type="submit"
            disabled={savingKey || !newApiKey.trim()}
            className="px-4 py-2.5 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] disabled:opacity-50 transition-colors font-medium flex-shrink-0"
          >
            {savingKey ? "Saving..." : "Save"}
          </button>
        </form>

        {keyMessage && (
          <p
            className={`mt-2 text-sm ${
              keyMessage.type === "success"
                ? "text-green-400"
                : "text-[#F2B8B5]"
            }`}
          >
            {keyMessage.text}
          </p>
        )}
      </section>

      {/* OpenAI API Key */}
      <section className="bg-[#2B2930] rounded-2xl border border-[#49454F] p-6">
        <h2 className="text-lg font-semibold text-[#E6E1E5] mb-1">
          OpenAI API Key
        </h2>
        <p className="text-sm text-[#938F99] mb-4">
          Used for Whisper meeting transcription (~$0.006/min). Your key is stored
          in Google Drive, never on our servers.
        </p>

        {hasOpenaiKey && maskedOpenaiKey && (
          <div className="flex items-center gap-2 mb-4 p-3 bg-green-400/10 border border-green-400/20 rounded-xl">
            <svg
              className="w-4 h-4 text-green-400 flex-shrink-0"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M5 13l4 4L19 7"
              />
            </svg>
            <span className="text-sm text-green-400">
              API key configured: <code className="font-mono">{maskedOpenaiKey}</code>
            </span>
          </div>
        )}

        <form onSubmit={saveOpenaiApiKey} className="flex gap-3">
          <input
            type="password"
            value={newOpenaiKey}
            onChange={(e) => setNewOpenaiKey(e.target.value)}
            placeholder={hasOpenaiKey ? "Enter new API key to update" : "sk-..."}
            className="flex-1 px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4] font-mono text-sm"
          />
          <button
            type="submit"
            disabled={savingOpenaiKey || !newOpenaiKey.trim()}
            className="px-4 py-2.5 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] disabled:opacity-50 transition-colors font-medium flex-shrink-0"
          >
            {savingOpenaiKey ? "Saving..." : "Save"}
          </button>
        </form>

        {openaiKeyMessage && (
          <p
            className={`mt-2 text-sm ${
              openaiKeyMessage.type === "success"
                ? "text-green-400"
                : "text-[#F2B8B5]"
            }`}
          >
            {openaiKeyMessage.text}
          </p>
        )}
      </section>

      {/* Private buckets — explanation only. There is no PIN: see lib/vault.tsx. */}
      <section className="bg-[#2B2930] rounded-2xl border border-[#49454F] p-6">
        <h2 className="text-lg font-semibold text-[#E6E1E5] mb-1">
          Private buckets
        </h2>
        <p className="text-sm text-[#938F99]">
          Buckets marked private on your phone are hidden here by default, and
          their contents are not sent to this browser until you choose to show
          them. Use the vault control in the sidebar to show or hide them.
        </p>
        <p className="text-sm text-[#938F99] mt-3">
          This is a visibility toggle, not a security lock — anyone using this
          browser can switch it on. It is meant to keep private buckets out of
          sight in passing, not to protect secrets.
        </p>
      </section>


      {/* Memory.md editor */}
      <section className="bg-[#2B2930] rounded-2xl border border-[#49454F] p-6">
        <div className="flex items-center justify-between mb-1">
          <h2 className="text-lg font-semibold text-[#E6E1E5]">
            Claude Memory
          </h2>
          <button
            onClick={saveMemory}
            disabled={savingMemory || !memoryDirty}
            className="px-4 py-2 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] disabled:opacity-50 transition-colors text-sm font-medium"
          >
            {savingMemory ? "Saving..." : memoryDirty ? "Save*" : "Save"}
          </button>
        </div>
        <p className="text-sm text-[#938F99] mb-4">
          This is <code className="bg-[#49454F]/40 px-1 rounded">memory.md</code>{" "}
          — prepended to every Claude conversation as system context.
        </p>

        {loadingMemory ? (
          <div className="flex items-center gap-2 py-8 justify-center text-[#938F99]">
            <svg className="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            Loading...
          </div>
        ) : (
          <textarea
            value={memory}
            onChange={(e) => {
              setMemory(e.target.value);
              setMemoryDirty(true);
            }}
            rows={16}
            className="w-full px-4 py-3 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] font-mono text-sm leading-relaxed resize-y focus:outline-none focus:border-[#6750A4]"
            spellCheck={false}
          />
        )}

        {memoryMessage && (
          <p
            className={`mt-2 text-sm ${
              memoryMessage.type === "success"
                ? "text-green-400"
                : "text-[#F2B8B5]"
            }`}
          >
            {memoryMessage.text}
          </p>
        )}
      </section>
    </div>
  );
}
