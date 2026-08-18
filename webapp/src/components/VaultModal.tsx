"use client";

import { useVault } from "@/hooks/useVault";

interface VaultModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export default function VaultModal({ isOpen, onClose }: VaultModalProps) {
  // No PIN: this is a visibility toggle, not a lock. See lib/vault.tsx for why the previous
  // PIN was removed rather than kept as a token gesture.
  const { isVaultOpen, toggleVault } = useVault();

  if (!isOpen) return null;

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    toggleVault();
    onClose();
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
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
              {isVaultOpen ? "Hide private buckets" : "Show private buckets"}
            </h2>
            <p className="text-sm text-[#CAC4D0]">
              {isVaultOpen
                ? "Private buckets will be hidden again."
                : "Keeps private buckets out of sight. Not a security lock — anyone using this browser can show them."}
            </p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="flex gap-3">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 rounded-xl border border-[#49454F] text-[#CAC4D0] hover:bg-[#49454F]/40 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              className="flex-1 px-4 py-2.5 rounded-xl bg-[#6750A4] text-white hover:bg-[#7965AF] transition-colors font-medium"
            >
              {isVaultOpen ? "Hide" : "Show"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
