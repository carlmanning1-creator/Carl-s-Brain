"use client";

import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
} from "react";

const SESSION_KEY = "vault_open";

/**
 * Vault state for the web app — a **visibility toggle, not a security control**.
 *
 * This deliberately has no PIN. It previously compared one in the browser against a value in
 * sessionStorage that defaulted to the literal string "vault", which was worse than nothing:
 * it implied a protection that anyone could bypass by reading devtools, or simply by setting
 * the session flag by hand. A lock that only looks like a lock is the kind you rely on without
 * meaning to.
 *
 * What it does provide, and what it is for: vault buckets stay out of sight in passing. The
 * server also withholds vault content unless this flag is set, so it is not shipped to the
 * browser by default — that part is a genuine improvement over filtering after the fact. But
 * the server trusts the client's claim, so anyone with the logged-in browser can ask for it.
 *
 * Carl's decision, on the basis that vault content here is "things I don't want people seeing
 * in passing", not secrets. If that changes, this is the place to start: verify a PIN
 * server-side against a hash in Drive and issue a signed, short-lived cookie, rather than
 * letting the client assert its own access.
 *
 * State lives in sessionStorage, so closing the browser re-hides everything.
 */
interface VaultContextValue {
  isVaultOpen: boolean;
  openVault: () => void;
  closeVault: () => void;
  toggleVault: () => void;
}

const VaultContext = createContext<VaultContextValue>({
  isVaultOpen: false,
  openVault: () => {},
  closeVault: () => {},
  toggleVault: () => {},
});

export function VaultProvider({ children }: { children: React.ReactNode }) {
  const [isVaultOpen, setIsVaultOpen] = useState(false);

  // Restore from sessionStorage on mount — survives navigation, not a browser restart.
  useEffect(() => {
    if (typeof window !== "undefined") {
      setIsVaultOpen(sessionStorage.getItem(SESSION_KEY) === "true");
    }
  }, []);

  const openVault = useCallback(() => {
    setIsVaultOpen(true);
    if (typeof window !== "undefined") {
      sessionStorage.setItem(SESSION_KEY, "true");
    }
  }, []);

  const closeVault = useCallback(() => {
    setIsVaultOpen(false);
    if (typeof window !== "undefined") {
      sessionStorage.removeItem(SESSION_KEY);
    }
  }, []);

  const toggleVault = useCallback(() => {
    if (isVaultOpen) closeVault();
    else openVault();
  }, [isVaultOpen, openVault, closeVault]);

  return (
    <VaultContext.Provider
      value={{ isVaultOpen, openVault, closeVault, toggleVault }}
    >
      {children}
    </VaultContext.Provider>
  );
}

export function useVaultContext(): VaultContextValue {
  return useContext(VaultContext);
}
