"use client";

import { useState, useRef, useCallback } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useSession, signOut } from "next-auth/react";
import Image from "next/image";
import VaultModal from "./VaultModal";
import { useVault } from "@/hooks/useVault";

const NAV_ITEMS = [
  {
    href: "/dashboard",
    label: "Dashboard",
    icon: (
      <svg
        className="w-5 h-5"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"
        />
      </svg>
    ),
  },
  {
    href: "/chat",
    label: "Chat",
    icon: (
      <svg
        className="w-5 h-5"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"
        />
      </svg>
    ),
  },
  {
    href: "/notes",
    label: "Notes",
    icon: (
      <svg
        className="w-5 h-5"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
        />
      </svg>
    ),
  },
  {
    href: "/todos",
    label: "Todos",
    icon: (
      <svg
        className="w-5 h-5"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
        />
      </svg>
    ),
  },
  {
    href: "/calendar",
    label: "Calendar",
    icon: (
      <svg
        className="w-5 h-5"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
        />
      </svg>
    ),
  },
];

export default function Sidebar() {
  const pathname = usePathname();
  const { data: session } = useSession();
  const { isVaultOpen } = useVault();
  const [vaultModalOpen, setVaultModalOpen] = useState(false);
  const longPressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleBrainMouseDown = useCallback(() => {
    longPressTimer.current = setTimeout(() => {
      setVaultModalOpen(true);
    }, 800);
  }, []);

  const handleBrainMouseUp = useCallback(() => {
    if (longPressTimer.current) {
      clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  }, []);

  const handleBrainTouchStart = useCallback(() => {
    longPressTimer.current = setTimeout(() => {
      setVaultModalOpen(true);
    }, 800);
  }, []);

  const handleBrainTouchEnd = useCallback(() => {
    if (longPressTimer.current) {
      clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  }, []);

  return (
    <>
      <aside className="fixed top-0 left-0 h-full w-60 bg-[#2B2930] border-r border-[#49454F] flex flex-col z-40">
        {/* Logo / Brain icon */}
        <div className="flex items-center gap-3 px-4 py-5 border-b border-[#49454F]">
          <button
            onMouseDown={handleBrainMouseDown}
            onMouseUp={handleBrainMouseUp}
            onMouseLeave={handleBrainMouseUp}
            onTouchStart={handleBrainTouchStart}
            onTouchEnd={handleBrainTouchEnd}
            className="relative w-10 h-10 rounded-xl bg-[#6750A4]/20 flex items-center justify-center cursor-pointer select-none flex-shrink-0 hover:bg-[#6750A4]/30 transition-colors"
            title="Hold to toggle vault"
            aria-label="Hold to toggle vault"
          >
            <Image
              src="/brain.svg"
              alt="Brain"
              width={24}
              height={24}
              className="w-6 h-6"
              style={{ filter: "invert(1) sepia(1) saturate(2) hue-rotate(230deg) brightness(0.9)" }}
            />
            {isVaultOpen && (
              <span className="absolute -top-1 -right-1 w-3 h-3 bg-[#6750A4] rounded-full border-2 border-[#2B2930]" />
            )}
          </button>
          <div className="min-w-0">
            <h1 className="text-base font-bold text-[#E6E1E5] leading-tight">
              Carl&apos;s Brain
            </h1>
            <p className="text-xs text-[#938F99] truncate">Second Brain</p>
          </div>
        </div>

        {/* Nav items */}
        <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
          {NAV_ITEMS.map((item) => {
            const isActive = pathname === item.href || pathname.startsWith(item.href + "/");
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`flex items-center gap-3 px-3 py-2.5 rounded-xl transition-colors group ${
                  isActive
                    ? "bg-[#6750A4]/20 text-[#D0BCFF]"
                    : "text-[#CAC4D0] hover:bg-[#49454F]/40 hover:text-[#E6E1E5]"
                }`}
              >
                <span
                  className={`transition-colors ${
                    isActive
                      ? "text-[#D0BCFF]"
                      : "text-[#938F99] group-hover:text-[#CAC4D0]"
                  }`}
                >
                  {item.icon}
                </span>
                <span className="text-sm font-medium">{item.label}</span>
              </Link>
            );
          })}

          {/* Separator */}
          <div className="border-t border-[#49454F] my-2" />

          <Link
            href="/settings"
            className={`flex items-center gap-3 px-3 py-2.5 rounded-xl transition-colors group ${
              pathname === "/settings"
                ? "bg-[#6750A4]/20 text-[#D0BCFF]"
                : "text-[#CAC4D0] hover:bg-[#49454F]/40 hover:text-[#E6E1E5]"
            }`}
          >
            <span
              className={`transition-colors ${
                pathname === "/settings"
                  ? "text-[#D0BCFF]"
                  : "text-[#938F99] group-hover:text-[#CAC4D0]"
              }`}
            >
              <svg
                className="w-5 h-5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                />
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                />
              </svg>
            </span>
            <span className="text-sm font-medium">Settings</span>
          </Link>

          {/* Vault toggle button */}
          <button
            onClick={() => setVaultModalOpen(true)}
            className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-[#CAC4D0] hover:bg-[#49454F]/40 hover:text-[#E6E1E5] transition-colors group"
          >
            <span className="text-[#938F99] group-hover:text-[#CAC4D0] transition-colors">
              <svg
                className="w-5 h-5"
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
            </span>
            <span className="text-sm font-medium">
              {isVaultOpen ? "Lock Vault" : "Unlock Vault"}
            </span>
          </button>
        </nav>

        {/* User profile */}
        {session?.user && (
          <div className="px-3 py-3 border-t border-[#49454F]">
            <div className="flex items-center gap-3 px-2 py-2">
              {session.user.image ? (
                <Image
                  src={session.user.image}
                  alt={session.user.name ?? "User"}
                  width={32}
                  height={32}
                  className="w-8 h-8 rounded-full"
                />
              ) : (
                <div className="w-8 h-8 rounded-full bg-[#6750A4] flex items-center justify-center text-white text-sm font-medium">
                  {session.user.name?.[0] ?? "C"}
                </div>
              )}
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium text-[#E6E1E5] truncate">
                  {session.user.name}
                </p>
                <p className="text-xs text-[#938F99] truncate">
                  {session.user.email}
                </p>
              </div>
              <button
                onClick={() => signOut({ callbackUrl: "/login" })}
                className="text-[#938F99] hover:text-[#CAC4D0] transition-colors"
                title="Sign out"
              >
                <svg
                  className="w-4 h-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"
                  />
                </svg>
              </button>
            </div>
          </div>
        )}
      </aside>

      <VaultModal
        isOpen={vaultModalOpen}
        onClose={() => setVaultModalOpen(false)}
      />
    </>
  );
}
