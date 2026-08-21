import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getMemoryWithVersion, updateMemory } from "@/lib/drive";

export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const { content, modifiedTime } = await getMemoryWithVersion(session.accessToken);
    // The stamp goes back with the content so a later PUT can prove it is editing the same
    // revision it read. Without it, the phone's next learned fact and this edit erase each other.
    return NextResponse.json({ content, modifiedTime });
  } catch (err) {
    console.error("GET /api/drive/memory error:", err);
    return NextResponse.json(
      { error: "Failed to fetch memory" },
      { status: 500 }
    );
  }
}

export async function PUT(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const body = await req.json();
    const { content, modifiedTime } = body;

    if (typeof content !== "string") {
      return NextResponse.json(
        { error: "content must be a string" },
        { status: 400 }
      );
    }

    const written = await updateMemory(
      session.accessToken,
      content,
      typeof modifiedTime === "string" ? modifiedTime : ""
    );
    if (!written) {
      return NextResponse.json(
        {
          error:
            "Your phone added something to memory since this page loaded. Reload to see it, then edit again.",
        },
        { status: 409 }
      );
    }
    return NextResponse.json({ success: true });
  } catch (err) {
    console.error("PUT /api/drive/memory error:", err);
    return NextResponse.json(
      { error: "Failed to update memory" },
      { status: 500 }
    );
  }
}
