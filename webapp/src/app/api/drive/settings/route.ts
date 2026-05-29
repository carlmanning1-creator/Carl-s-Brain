import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getApiKey, saveApiKey, getOpenaiApiKey, saveOpenaiApiKey } from "@/lib/drive";

export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const [apiKey, openaiKey] = await Promise.all([
      getApiKey(session.accessToken),
      getOpenaiApiKey(session.accessToken),
    ]);
    // Return masked versions — never expose the full key to the browser
    const masked = apiKey
      ? `${apiKey.slice(0, 10)}...${apiKey.slice(-4)}`
      : null;
    const maskedOpenai = openaiKey
      ? `${openaiKey.slice(0, 8)}...${openaiKey.slice(-4)}`
      : null;
    return NextResponse.json({
      hasApiKey: !!apiKey,
      maskedKey: masked,
      hasOpenaiKey: !!openaiKey,
      maskedOpenaiKey: maskedOpenai,
    });
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
    const { apiKey, openaiApiKey } = body;

    if (!apiKey && !openaiApiKey) {
      return NextResponse.json(
        { error: "apiKey or openaiApiKey is required" },
        { status: 400 }
      );
    }

    if (apiKey && typeof apiKey === "string") {
      await saveApiKey(session.accessToken, apiKey);
    }
    if (openaiApiKey && typeof openaiApiKey === "string") {
      await saveOpenaiApiKey(session.accessToken, openaiApiKey);
    }
    return NextResponse.json({ success: true });
  } catch (err) {
    console.error("POST /api/drive/settings error:", err);
    return NextResponse.json(
      { error: "Failed to save settings" },
      { status: 500 }
    );
  }
}
