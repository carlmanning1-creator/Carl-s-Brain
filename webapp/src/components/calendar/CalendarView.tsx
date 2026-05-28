"use client";

import { useEffect, useState, useCallback } from "react";
import type { CalendarEvent } from "@/lib/types";

function formatTime(dateStr: string, allDay: boolean): string {
  if (allDay) return "All day";
  return new Date(dateStr).toLocaleTimeString("en-AU", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: true,
  });
}

function formatDateHeader(dateStr: string): string {
  const d = new Date(dateStr);
  const now = new Date();
  const tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);

  const isToday =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();

  const isTomorrow =
    d.getFullYear() === tomorrow.getFullYear() &&
    d.getMonth() === tomorrow.getMonth() &&
    d.getDate() === tomorrow.getDate();

  if (isToday) return "Today";
  if (isTomorrow) return "Tomorrow";
  return d.toLocaleDateString("en-AU", {
    weekday: "long",
    month: "long",
    day: "numeric",
  });
}

function groupEventsByDate(
  events: CalendarEvent[]
): { date: string; events: CalendarEvent[] }[] {
  const groups: Record<string, CalendarEvent[]> = {};
  for (const event of events) {
    const dateKey = event.start.split("T")[0];
    if (!groups[dateKey]) groups[dateKey] = [];
    groups[dateKey].push(event);
  }
  return Object.entries(groups)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, events]) => ({ date, events }));
}

interface CreateEventFormProps {
  onSubmit: (data: {
    title: string;
    start: string;
    end: string;
    allDay: boolean;
    location: string;
    description: string;
  }) => Promise<void>;
  onClose: () => void;
}

function CreateEventForm({ onSubmit, onClose }: CreateEventFormProps) {
  const [title, setTitle] = useState("");
  const [startDate, setStartDate] = useState(
    new Date().toISOString().split("T")[0]
  );
  const [startTime, setStartTime] = useState("09:00");
  const [endTime, setEndTime] = useState("10:00");
  const [allDay, setAllDay] = useState(false);
  const [location, setLocation] = useState("");
  const [description, setDescription] = useState("");
  const [saving, setSaving] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setSaving(true);
    try {
      await onSubmit({
        title: title.trim(),
        start: allDay ? startDate : `${startDate}T${startTime}:00`,
        end: allDay ? startDate : `${startDate}T${endTime}:00`,
        allDay,
        location,
        description,
      });
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />
      <div className="relative z-10 w-full max-w-md bg-[#2B2930] border border-[#49454F] rounded-2xl p-6 shadow-2xl">
        <h2 className="text-lg font-semibold text-[#E6E1E5] mb-5">
          New Event
        </h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
              Title <span className="text-red-400">*</span>
            </label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Event title"
              autoFocus
              className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4]"
            />
          </div>

          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              id="allDay"
              checked={allDay}
              onChange={(e) => setAllDay(e.target.checked)}
              className="w-4 h-4 accent-[#6750A4]"
            />
            <label
              htmlFor="allDay"
              className="text-sm text-[#CAC4D0] cursor-pointer"
            >
              All day event
            </label>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
                Date
              </label>
              <input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
              />
            </div>
            {!allDay && (
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
                    Start
                  </label>
                  <input
                    type="time"
                    value={startTime}
                    onChange={(e) => setStartTime(e.target.value)}
                    className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
                    End
                  </label>
                  <input
                    type="time"
                    value={endTime}
                    onChange={(e) => setEndTime(e.target.value)}
                    className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
                  />
                </div>
              </div>
            )}
          </div>

          <div>
            <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
              Location (optional)
            </label>
            <input
              type="text"
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              placeholder="Add location"
              className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4]"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-[#CAC4D0] mb-1.5">
              Description (optional)
            </label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Add description"
              rows={2}
              className="w-full px-3 py-2.5 bg-[#1C1B1F] border border-[#49454F] rounded-xl text-[#E6E1E5] placeholder-[#938F99] focus:outline-none focus:border-[#6750A4] resize-none"
            />
          </div>

          <div className="flex gap-3 pt-1">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 rounded-xl border border-[#49454F] text-[#CAC4D0] hover:bg-[#49454F]/40 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving || !title.trim()}
              className="flex-1 px-4 py-2.5 rounded-xl bg-[#6750A4] text-white hover:bg-[#7965AF] disabled:opacity-50 transition-colors font-medium flex items-center justify-center gap-2"
            >
              {saving ? (
                <>
                  <svg className="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                  </svg>
                  Saving...
                </>
              ) : "Create Event"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function CalendarView() {
  const [events, setEvents] = useState<CalendarEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreateForm, setShowCreateForm] = useState(false);

  const fetchEvents = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch("/api/calendar");
      if (!res.ok) throw new Error("Failed to fetch events");
      const data = await res.json();
      setEvents(data.events ?? []);
    } catch {
      setError("Failed to load calendar events.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchEvents();
  }, [fetchEvents]);

  async function createEvent(data: {
    title: string;
    start: string;
    end: string;
    allDay: boolean;
    location: string;
    description: string;
  }) {
    try {
      const res = await fetch("/api/calendar", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
      });
      if (!res.ok) throw new Error("Failed to create event");
      setShowCreateForm(false);
      await fetchEvents();
    } catch {
      alert("Failed to create event. Please try again.");
    }
  }

  const grouped = groupEventsByDate(events);

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-[#E6E1E5]">Calendar</h1>
          <p className="text-sm text-[#938F99]">Next 14 days</p>
        </div>
        <button
          onClick={() => setShowCreateForm(true)}
          className="flex items-center gap-2 px-4 py-2 bg-[#6750A4] text-white rounded-xl hover:bg-[#7965AF] transition-colors font-medium"
        >
          <svg
            className="w-4 h-4"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 4v16m8-8H4"
            />
          </svg>
          New Event
        </button>
      </div>

      {loading ? (
        <div className="flex items-center gap-3 py-12 justify-center text-[#938F99]">
          <svg className="w-5 h-5 animate-spin" fill="none" viewBox="0 0 24 24">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
          </svg>
          Loading calendar...
        </div>
      ) : error ? (
        <div className="text-center py-12">
          <p className="text-[#F2B8B5] mb-3">{error}</p>
          <button
            onClick={fetchEvents}
            className="px-4 py-2 bg-[#6750A4] text-white rounded-lg hover:bg-[#7965AF] transition-colors"
          >
            Retry
          </button>
        </div>
      ) : grouped.length === 0 ? (
        <div className="text-center py-16">
          <div className="w-16 h-16 rounded-2xl bg-[#2B2930] flex items-center justify-center mx-auto mb-4">
            <svg className="w-8 h-8 text-[#49454F]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
            </svg>
          </div>
          <p className="text-[#938F99]">No events in the next 14 days</p>
        </div>
      ) : (
        <div className="space-y-6">
          {grouped.map(({ date, events: dayEvents }) => (
            <div key={date}>
              {/* Date header */}
              <h2 className="text-sm font-semibold text-[#CAC4D0] mb-2 uppercase tracking-wider">
                {formatDateHeader(date + "T00:00:00")}
              </h2>
              <div className="space-y-2">
                {dayEvents.map((event) => (
                  <div
                    key={event.id}
                    className="flex items-start gap-4 p-4 bg-[#2B2930] border border-[#49454F] rounded-xl hover:border-[#6750A4]/30 transition-colors"
                  >
                    {/* Time column */}
                    <div className="w-16 flex-shrink-0 text-right">
                      <span className="text-sm text-[#938F99]">
                        {formatTime(event.start, event.allDay)}
                      </span>
                    </div>

                    {/* Color dot */}
                    <div className="w-2 h-2 rounded-full bg-[#6750A4] mt-2 flex-shrink-0" />

                    {/* Event details */}
                    <div className="flex-1 min-w-0">
                      <p className="font-medium text-[#E6E1E5] truncate">
                        {event.title}
                      </p>
                      {event.location && (
                        <p className="text-sm text-[#938F99] mt-0.5 truncate">
                          📍 {event.location}
                        </p>
                      )}
                      {event.description && (
                        <p className="text-sm text-[#938F99] mt-0.5 line-clamp-2">
                          {event.description}
                        </p>
                      )}
                    </div>

                    {/* End time */}
                    {!event.allDay && (
                      <div className="flex-shrink-0 text-right">
                        <span className="text-xs text-[#49454F]">
                          until {formatTime(event.end, false)}
                        </span>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {showCreateForm && (
        <CreateEventForm
          onSubmit={createEvent}
          onClose={() => setShowCreateForm(false)}
        />
      )}
    </div>
  );
}
