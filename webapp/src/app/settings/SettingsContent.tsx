"use client";

import { useState, useEffect, useCallback } from "react";
import { useVault } from "@/hooks/useVault";

export default function SettingsContent() {
  const { isVaultOpen, getStoredPin, setStoredPin } = useVault();

  // API key state
  const [hasApiKey, setHasApiKey] = useState(false);
  const [maskedKey, setMaskedKey] = useState<string | null>(null);
  const [newApiKey, setNewApiKey] = useState("");
  const [savingKey, setSavingKey] = useState(false);
  const [keyMessage, setKeyMessage] = useState<{
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

  // PIN change state
  const [currentPin, setCurrentPin] = useState("");
  const [newPin, setNewPin] = useState("");
  const [confirmPin, setConfirmPin] = useState("");
  const [pinMessage, setPinMessage] = useState<{
    type: "success" | "error";
    text: string;
  } | null>(null);

  const fetchSettings = useCallback(async () => {
    try {
      const res = await fetch("/api/drive/settings");
      if (res.ok) {
        const data = await res.json();
        setHasApiKey(data.hasApiKey);
        setMaskedKey(data.maskedKey);
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

  function changePin(e: React.FormEvent) {
    e.preventDefault();
    setPinMessage(null);
    const stored = getStoredPin();
    if (currentPin !== stored) {
      setPinMessage({ type: "error", text: "Current PIN is incorrect." });
      return;
    }
    if (newPin.length < 4) {
      setPinMessage({ type: "error", text: "New PIN must be at least 4 characters." });
      return;
    }
    if (newPin !== confirmPin) {
      setPinMessage({ type: "error", text: "New PINs do not match." });
      return;
    }
    setStoredPin(newPin);
    setPinMessage({ type: "success", text: "Vault PIN updated." });
    setCurrentPin("");
    setNewPin("");
    setConfirmPin("");
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

      {/* Vault PIN */}
      <section className="bg-[#2B2930] rounded-2xl border border-[#49454F] p-6">
        <h2 className="text-lg font-semibold text-[#E6E1E5] mb-1">
          Vault PIN
        </h2>
        <p className="text-sm text-[#938F99] mb-4">
          PIN is stored in your browser session only and cleared when you close
          the tab. Default PIN is{" "}
          <code className="bg-[#49454F]/40 px-1 rounded">vault</code>.
        </p>

        <div className="flex items-center gap-2 mb-4 p-3 bg-[#1C1B1F] rounded-xl">
          <svg
            className={`w-4 h-4 flex-shrink-0 ${
              isVaultOpen ? "text-[#6750A4]" : "text-[#938F99]"
            }`}
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d={
                isVaultOpen
                  ? "M8 11V7a4 4 0 118 0m-4 8v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2z"
                  : "M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
              }
            />
          </svg>
          <span className="text-sm text-[#CAC4D0]">
            Vault is currently{" "}
            <strong className={isVaultOpen ? "text-[#6750A4]" : "text-[#938F99]"}>
              {isVaultOpen ? "unlocked" : "locked"}
            </strong>
          </span>
        </div>

        <form onSubmit={changePin} className="space-y-3">
          <input
            type="password"
            value={currentPin}
            onChange={(e) => setCurrentPin(e.target.value)}
            placeholder="Current PIN"
            className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4]"
          />
          <div className="grid grid-cols-2 gap-3">
            <input
              type="password"
              value={newPin}
              onChange={(e) => setNewPin(e.target.value)}
              placeholder="New PIN"
              className="px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4]"
            />
            <input
              type="password"
              value={confirmPin}
              onChange={(e) => setConfirmPin(e.target.value)}
              placeholder="Confirm new PIN"
              className="px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4]"
            />
          </div>
          <button
            type="submit"
            disabled={!currentPin || !newPin || !confirmPin}
            className="px-4 py-2.5 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] disabled:opacity-50 transition-colors font-medium"
          >
            Update PIN
          </button>
        </form>

        {pinMessage && (
          <p
            className={`mt-2 text-sm ${
              pinMessage.type === "success" ? "text-green-400" : "text-[#F2B8B5]"
            }`}
          >
            {pinMessage.text}
          </p>
        )}
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
