"use client";

import { useState } from "react";
import { useVault } from "@/hooks/useVault";

interface VaultModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export default function VaultModal({ isOpen, onClose }: VaultModalProps) {
  const { isVaultOpen, openVault, closeVault } = useVault();
  const [pin, setPin] = useState("");
  const [error, setError] = useState("");

  if (!isOpen) return null;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    if (isVaultOpen) {
      closeVault();
      setPin("");
      onClose();
      return;
    }
    const success = openVault(pin);
    if (success) {
      setPin("");
      onClose();
    } else {
      setError("Incorrect PIN.");
      setPin("");
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          setPin("");
          setError("");
          onClose();
        }
      }}
    >
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" />

      {/* Modal */}
      <div className="relative z-10 w-full max-w-sm mx-4 bg-[#2B2930] border border-[#49454F] rounded-2xl p-6 shadow-2xl">
        <div className="flex items-center gap-3 mb-5">
          <div className="w-10 h-10 rounded-full bg-[#6750A4]/20 flex items-center justify-center">
            <svg
              className="w-5 h-5 text-[#6750A4]"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
              />
            </svg>
          </div>
          <div>
            <h2 className="text-lg font-semibold text-[#E6E1E5]">
              {isVaultOpen ? "Lock Vault" : "Unlock Vault"}
            </h2>
            <p className="text-sm text-[#CAC4D0]">
              {isVaultOpen
                ? "This will hide vault buckets"
                : "Enter PIN to access private buckets"}
            </p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          {!isVaultOpen && (
            <div>
              <input
                type="password"
                value={pin}
                onChange={(e) => {
                  setPin(e.target.value);
                  setError("");
                }}
                placeholder="Enter PIN"
                autoFocus
                className="w-full px-4 py-3 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4] text-center text-xl tracking-widest"
              />
              {error && (
                <p className="mt-2 text-sm text-[#F2B8B5] text-center">
                  {error}
                </p>
              )}
            </div>
          )}

          <div className="flex gap-3">
            <button
              type="button"
              onClick={() => {
                setPin("");
                setError("");
                onClose();
              }}
              className="flex-1 px-4 py-2.5 rounded-xl border border-[#49454F] text-[#CAC4D0] hover:bg-[#49454F]/40 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="flex-1 px-4 py-2.5 rounded-xl bg-[#6750A4] text-white hover:bg-[#7965AF] transition-colors font-medium"
            >
              {isVaultOpen ? "Lock" : "Unlock"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
