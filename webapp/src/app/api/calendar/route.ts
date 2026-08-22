import { getServerSession } from "next-auth";
import { NextRequest, NextResponse } from "next/server";
import { authOptions } from "@/lib/auth";
import { google } from "googleapis";
import type { CalendarEvent } from "@/lib/types";
import { getPreferences } from "@/lib/drive";

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

    // Every calendar Carl has, minus the ones he switched off — matching the phone.
    //
    // This used to read `calendarId: "primary"` and nothing else, so the web app showed a
    // strictly smaller diary than the phone: anything on a shared or secondary calendar (SES,
    // a household calendar) was simply absent, with no indication a calendar existed at all.
    // The exclusions come from preferences.json, which the phone already publishes, so the two
    // now hide the same calendars rather than each having its own idea.
    const excluded = new Set((await getPreferences(session.accessToken)).excludedCalendarIds ?? []);
    const list = await calendar.calendarList.list({ maxResults: 100 });
    const calendars = (list.data.items ?? []).filter(
      // The primary calendar is never excludable, mirroring CalendarRepository.isIncluded: an
      // exclusion that somehow names it must not lock Carl out of his own events.
      (c) => c.primary === true || c.id === "primary" || !excluded.has(c.id ?? "")
    );

    const perCalendar = await Promise.all(
      calendars.map(async (cal) => {
        if (!cal.id) return [];
        try {
          const res = await calendar.events.list({
            calendarId: cal.id,
            timeMin: now.toISOString(),
            timeMax: twoWeeksLater.toISOString(),
            singleEvents: true,
            orderBy: "startTime",
            maxResults: 100,
          });
          return (res.data.items ?? []).map((item) => ({
            id: item.id ?? "",
            title: item.summary ?? "(No title)",
            start: item.start?.dateTime ?? item.start?.date ?? "",
            end: item.end?.dateTime ?? item.end?.date ?? "",
            allDay: !item.start?.dateTime,
            location: item.location ?? undefined,
            description: item.description ?? undefined,
            // The calendar's own name, not the organiser's: the phone labels events this way,
            // and "which calendar is this on" is the useful question.
            calendarName: cal.summary ?? undefined,
          }));
        } catch {
          // One calendar Carl cannot read must not empty the whole diary.
          return [];
        }
      })
    );

    const events: CalendarEvent[] = perCalendar
      .flat()
      .sort((a, b) => new Date(a.start).getTime() - new Date(b.start).getTime());

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
