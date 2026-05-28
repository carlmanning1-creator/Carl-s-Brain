import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { google } from "googleapis";
import type { CalendarEvent } from "@/lib/types";

function getCalendarClient(accessToken: string) {
  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  return google.calendar({ version: "v3", auth });
}

export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const calendar = getCalendarClient(session.accessToken);

    const now = new Date();
    const twoWeeksLater = new Date();
    twoWeeksLater.setDate(twoWeeksLater.getDate() + 14);

    const res = await calendar.events.list({
      calendarId: "primary",
      timeMin: now.toISOString(),
      timeMax: twoWeeksLater.toISOString(),
      singleEvents: true,
      orderBy: "startTime",
      maxResults: 50,
    });

    const events: CalendarEvent[] = (res.data.items ?? []).map((item) => ({
      id: item.id ?? "",
      title: item.summary ?? "(No title)",
      start: item.start?.dateTime ?? item.start?.date ?? "",
      end: item.end?.dateTime ?? item.end?.date ?? "",
      allDay: !item.start?.dateTime,
      location: item.location ?? undefined,
      description: item.description ?? undefined,
      calendarName: item.organizer?.displayName ?? undefined,
    }));

    return NextResponse.json({ events });
  } catch (err) {
    console.error("GET /api/calendar error:", err);
    return NextResponse.json(
      { error: "Failed to fetch calendar events" },
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
    const { title, start, end, allDay, location, description } = body;

    if (!title || !start) {
      return NextResponse.json(
        { error: "title and start are required" },
        { status: 400 }
      );
    }

    const calendar = getCalendarClient(session.accessToken);

    const event = await calendar.events.insert({
      calendarId: "primary",
      requestBody: {
        summary: title,
        location,
        description,
        start: allDay
          ? { date: start.split("T")[0] }
          : { dateTime: start, timeZone: "Australia/Sydney" },
        end: allDay
          ? { date: (end || start).split("T")[0] }
          : {
              dateTime: end || start,
              timeZone: "Australia/Sydney",
            },
      },
    });

    return NextResponse.json({
      event: {
        id: event.data.id,
        title: event.data.summary,
        start: event.data.start?.dateTime ?? event.data.start?.date,
        end: event.data.end?.dateTime ?? event.data.end?.date,
        allDay: !event.data.start?.dateTime,
      },
    });
  } catch (err) {
    console.error("POST /api/calendar error:", err);
    return NextResponse.json(
      { error: "Failed to create calendar event" },
      { status: 500 }
    );
  }
}
