import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { getCalendarClient, getUpcomingEvents } from "@/lib/calendar";

export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  try {
    const events = await getUpcomingEvents(session.accessToken, 14);
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
