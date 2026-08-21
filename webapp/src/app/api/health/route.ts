import { NextResponse } from "next/server";

/**
 * Liveness only.
 *
 * This used to report which environment variables were set and echo NEXTAUTH_URL, on a public
 * URL with no auth — a free fingerprint of the deployment for anyone who found it. Whether the
 * app is configured correctly is answered by whether it works.
 */
export async function GET() {
  return NextResponse.json({ status: "ok" });
}
