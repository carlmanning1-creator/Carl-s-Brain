"use client";

import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
} from "react";
import { VAULT_BUCKETS } from "./types";

const SESSION_KEY = "vault_open";
const PIN_KEY = "vault_pin";
const DEFAULT_PIN = "vault";

interface VaultContextValue {
  isVaultOpen: boolean;
  vaultBuckets: string[];
  openVault: (pin: string) => boolean;
  closeVault: () => void;
  getStoredPin: () => string;
  setStoredPin: (pin: string) => void;
}

const VaultContext = createContext<VaultContextValue>({
  isVaultOpen: false,
  vaultBuckets: VAULT_BUCKETS,
  openVault: () => false,
  closeVault: () => {},
  getStoredPin: () => DEFAULT_PIN,
  setStoredPin: () => {},
});

export function VaultProvider({ children }: { children: React.ReactNode }) {
  const [isVaultOpen, setIsVaultOpen] = useState(false);

  // Restore vault state from sessionStorage on mount
  useEffect(() => {
    if (typeof window !== "undefined") {
      const stored = sessionStorage.getItem(SESSION_KEY);
      if (stored === "true") setIsVaultOpen(true);
    }
  }, []);

  const getStoredPin = useCallback((): string => {
    if (typeof window === "undefined") return DEFAULT_PIN;
    return sessionStorage.getItem(PIN_KEY) ?? DEFAULT_PIN;
  }, []);

  const setStoredPin = useCallback((pin: string) => {
    if (typeof window !== "undefined") {
      sessionStorage.setItem(PIN_KEY, pin);
    }
  }, []);

  const openVault = useCallback(
    (pin: string): boolean => {
      const correct = getStoredPin();
      if (pin === correct) {
        setIsVaultOpen(true);
        sessionStorage.setItem(SESSION_KEY, "true");
        return true;
      }
      return false;
    },
    [getStoredPin]
  );

  const closeVault = useCallback(() => {
    setIsVaultOpen(false);
    sessionStorage.removeItem(SESSION_KEY);
  }, []);

  return (
    <VaultContext.Provider
      value={{
        isVaultOpen,
        vaultBuckets: VAULT_BUCKETS,
        openVault,
        closeVault,
        getStoredPin,
        setStoredPin,
      }}
    >
      {children}
    </VaultContext.Provider>
  );
}

export function useVaultContext(): VaultContextValue {
  return useContext(VaultContext);
}
