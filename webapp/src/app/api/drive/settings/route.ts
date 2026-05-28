import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getApiKey, saveApiKey } from "@/lib/drive";

export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const apiKey = await getApiKey(session.accessToken);
    // Return a masked version — never expose the full key to the browser
    const masked = apiKey
      ? `${apiKey.slice(0, 10)}...${apiKey.slice(-4)}`
      : null;
    return NextResponse.json({ hasApiKey: !!apiKey, maskedKey: masked });
  } catch (err) {
    console.error("GET /api/drive/settings error:", err);
    return NextResponse.json(
      { error: "Failed to fetch settings" },
      { status: 500 }
    );
  }
}

export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const body = await req.json();
    const { apiKey } = body;

    if (!apiKey || typeof apiKey !== "string") {
      return NextResponse.json(
        { error: "apiKey is required" },
        { status: 400 }
      );
    }

    await saveApiKey(session.accessToken, apiKey);
    return NextResponse.json({ success: true });
  } catch (err) {
    console.error("POST /api/drive/settings error:", err);
    return NextResponse.json(
      { error: "Failed to save settings" },
      { status: 500 }
    );
  }
}
