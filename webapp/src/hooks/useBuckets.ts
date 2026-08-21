"use client";

import { useCallback, useEffect, useState } from "react";
import { DEFAULT_BUCKETS, type BucketConfig } from "@/lib/types";

/**
 * Carl's real buckets, from buckets.json.
 *
 * Starts from the built-in six so a picker is never empty on first render, then replaces them
 * with whatever the phone actually published. The server withholds vault buckets unless the
 * vault is open, so nothing here needs to filter.
 *
 * @param isVaultOpen passed through to the server, which decides what to send.
 */
export function useBuckets(isVaultOpen = false) {
  const [buckets, setBuckets] = useState<BucketConfig[]>(DEFAULT_BUCKETS);
  const [loaded, setLoaded] = useState(false);

  const fetchBuckets = useCallback(async () => {
    try {
      const res = await fetch(
        `/api/drive/buckets${isVaultOpen ? "?vault=open" : ""}`
      );
      if (!res.ok) return;
      const data = await res.json();
      if (Array.isArray(data.buckets) && data.buckets.length > 0) {
        setBuckets(data.buckets);
      }
    } catch {
      // Keep the defaults — a failed lookup must not empty every picker in the app.
    } finally {
      setLoaded(true);
    }
  }, [isVaultOpen]);

  useEffect(() => {
    fetchBuckets();
  }, [fetchBuckets]);

  return { buckets, loaded, refresh: fetchBuckets };
}
