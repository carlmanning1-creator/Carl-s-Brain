import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getOpenaiApiKey } from "@/lib/drive";

export async function POST(req: NextRequest) {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const openaiApiKey = await getOpenaiApiKey(session.accessToken);
    if (!openaiApiKey) {
      return NextResponse.json(
        { error: "No OpenAI API key configured" },
        { status: 400 }
      );
    }

    const formData = await req.formData();
    const audioBlob = formData.get("audio") as Blob | null;
    if (!audioBlob) {
      return NextResponse.json({ error: "audio is required" }, { status: 400 });
    }

    // Forward to OpenAI Whisper
    const whisperForm = new FormData();
    whisperForm.append("model", "whisper-1");
    whisperForm.append("response_format", "text");
    whisperForm.append("file", audioBlob, "recording.webm");

    const whisperRes = await fetch("https://api.openai.com/v1/audio/transcriptions", {
      method: "POST",
      headers: { Authorization: `Bearer ${openaiApiKey}` },
      body: whisperForm,
    });

    if (!whisperRes.ok) {
      const errBody = await whisperRes.text();
      console.error("Whisper API error:", whisperRes.status, errBody);
      return NextResponse.json(
        { error: `Whisper API error: ${whisperRes.status}` },
        { status: 502 }
      );
    }

    const transcript = await whisperRes.text(); // response_format=text returns plain text
    return NextResponse.json({ transcript: transcript.trim() });
  } catch (err) {
    console.error("POST /api/meetings/transcribe error:", err);
    return NextResponse.json({ error: "Transcription failed" }, { status: 500 });
  }
}
