import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getBucketConfig } from "@/lib/drive";
import { DEFAULT_BUCKETS } from "@/lib/types";

/**
 * GET /api/drive/buckets?vault=open
 *
 * Carl's real bucket list, as the phone publishes it to buckets.json.
 *
 * Every picker and filter in this app used to render a hardcoded list of six. A bucket he
 * created on the phone therefore never appeared here, and editing an item that lived in one
 * showed a dropdown with no matching option — one stray click silently refiled it into
 * something else. Meanwhile a bucket he had just marked vault still appeared in the filter
 * list, naming it while the vault was locked.
 *
 * Vault buckets are withheld unless the vault is open, so the names themselves stay private.
 * A missing buckets.json falls back to the built-in six rather than returning nothing — a
 * bucket picker with no options would make the app unusable, and those six are the set the
 * phone seeds anyway.
 */
export async function GET(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const vaultOpen = req.nextUrl.searchParams.get("vault") === "open";
    const config = (await getBucketConfig(session.accessToken)) ?? DEFAULT_BUCKETS;
    const visible = vaultOpen ? config : config.filter((b) => !b.isVault);
    return NextResponse.json({ buckets: visible });
  } catch (err) {
    console.error("GET /api/drive/buckets error:", err);
    return NextResponse.json(
      { error: "Failed to fetch buckets" },
      { status: 500 }
    );
  }
}
