import { google } from "googleapis";
import type { CalendarEvent } from "./types";
import { getPreferences } from "./drive";

/**
 * Reading Carl's diary, shared by the calendar route and by unleashed Chat's get_calendar
 * tool.
 *
 * Extracted from the route so the two cannot drift: a tool that read only the primary calendar
 * while the page read all of them would tell Carl his Tuesday was free when SES training is on
 * it, which is exactly the bug the route itself had until August 2026.
 */

export function getCalendarClient(accessToken: string) {
  const auth = new google.auth.OAuth2();
  auth.setCredentials({ access_token: accessToken });
  return google.calendar({ version: "v3", auth });
}

/**
 * Every calendar Carl has, minus the ones he switched off — matching the phone.
 *
 * The exclusions come from preferences.json, which the phone publishes, so the two clients
 * hide the same calendars rather than each having its own idea. The primary calendar is never
 * excludable, mirroring CalendarRepository.isIncluded: an exclusion that somehow names it must
 * not lock Carl out of his own events.
 */
export async function getUpcomingEvents(
  accessToken: string,
  daysAhead = 14
): Promise<CalendarEvent[]> {
  const calendar = getCalendarClient(accessToken);

  const now = new Date();
  const until = new Date();
  until.setDate(until.getDate() + daysAhead);

  const excluded = new Set(
    (await getPreferences(accessToken)).excludedCalendarIds ?? []
  );
  const list = await calendar.calendarList.list({ maxResults: 100 });
  const calendars = (list.data.items ?? []).filter(
    (c) => c.primary === true || c.id === "primary" || !excluded.has(c.id ?? "")
  );

  const perCalendar = await Promise.all(
    calendars.map(async (cal) => {
      if (!cal.id) return [];
      try {
        const res = await calendar.events.list({
          calendarId: cal.id,
          timeMin: now.toISOString(),
          timeMax: until.toISOString(),
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

  return perCalendar
    .flat()
    .sort((a, b) => new Date(a.start).getTime() - new Date(b.start).getTime());
}
