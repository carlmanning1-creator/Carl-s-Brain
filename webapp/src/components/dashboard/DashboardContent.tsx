"use client";

import { useEffect, useState, useCallback } from "react";
import Link from "next/link";
import { useVault } from "@/hooks/useVault";
import type { TodoSyncDto, CalendarEvent } from "@/lib/types";
import { VAULT_BUCKETS } from "@/lib/types";

const PRIORITY_ORDER = ["URGENT", "HIGH", "NORMAL", "SOMEDAY"] as const;
const PRIORITY_COLORS: Record<string, string> = {
  URGENT: "text-red-400 bg-red-400/10 border-red-400/20",
  HIGH: "text-orange-400 bg-orange-400/10 border-orange-400/20",
  NORMAL: "text-blue-400 bg-blue-400/10 border-blue-400/20",
  SOMEDAY: "text-[#938F99] bg-[#938F99]/10 border-[#938F99]/20",
};

function formatEventTime(event: CalendarEvent): string {
  if (event.allDay) return "All day";
  const start = new Date(event.start);
  return start.toLocaleTimeString("en-AU", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: true,
  });
}

function isToday(dateStr: string): boolean {
  const d = new Date(dateStr);
  const now = new Date();
  return (
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  );
}

function formatEventDate(dateStr: string): string {
  const d = new Date(dateStr);
  const now = new Date();
  const tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);

  if (isToday(dateStr)) return "Today";
  if (
    d.getFullYear() === tomorrow.getFullYear() &&
    d.getMonth() === tomorrow.getMonth() &&
    d.getDate() === tomorrow.getDate()
  ) {
    return "Tomorrow";
  }
  return d.toLocaleDateString("en-AU", { weekday: "short", month: "short", day: "numeric" });
}

export default function DashboardContent() {
  const { isVaultOpen } = useVault();
  const [todos, setTodos] = useState<TodoSyncDto[]>([]);
  const [events, setEvents] = useState<CalendarEvent[]>([]);
  const [briefing, setBriefing] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [briefingLoading, setBriefingLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const [todosRes, eventsRes] = await Promise.all([
        fetch("/api/drive/todos"),
        fetch("/api/calendar"),
      ]);

      if (!todosRes.ok || !eventsRes.ok) {
        throw new Error("Failed to fetch data");
      }

      const todosData = await todosRes.json();
      const eventsData = await eventsRes.json();

      setTodos(todosData.todos ?? []);
      setEvents(eventsData.events ?? []);
    } catch (err) {
      setError("Failed to load dashboard data. Please refresh.");
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Generate briefing after data is loaded
  useEffect(() => {
    if (loading || todos.length === 0 || briefing) return;

    async function generateBriefing() {
      setBriefingLoading(true);
      try {
        const todayEvents = events
          .filter((e) => isToday(e.start))
          .map((e) => `- ${e.title} at ${formatEventTime(e)}`)
          .join("\n");

        const urgentTodos = todos
          .filter(
            (t) =>
              !t.isDone &&
              (t.priority === "URGENT" || t.priority === "HIGH") &&
              (isVaultOpen || !VAULT_BUCKETS.includes(t.bucket))
          )
          .map((t) => `- [${t.priority}] ${t.title} (${t.bucket})`)
          .join("\n");

        const response = await fetch("/api/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            messages: [
              {
                role: "user",
                content: `Generate a concise morning briefing paragraph for today. Here's what's on:

Calendar events today:
${todayEvents || "No events scheduled today."}

Urgent/High priority todos:
${urgentTodos || "No urgent items."}

Write a natural, supportive 2-3 sentence briefing. Mention the most important items and offer a brief focus suggestion. Use Australian English.`,
              },
            ],
          }),
        });

        if (!response.ok) {
          const data = await response.json();
          if (data.error?.includes("API key")) {
            setBriefing(
              "Add your Anthropic API key in Settings to enable AI briefings."
            );
            return;
          }
          throw new Error("Briefing failed");
        }

        const reader = response.body?.getReader();
        const decoder = new TextDecoder();
        let text = "";
        if (reader) {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            text += decoder.decode(value, { stream: true });
            setBriefing(text);
          }
        }
      } catch {
        setBriefing("Unable to generate briefing right now.");
      } finally {
        setBriefingLoading(false);
      }
    }

    generateBriefing();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading]);

  const visibleTodos = todos.filter(
    (t) =>
      !t.isDone &&
      (t.priority === "URGENT" || t.priority === "HIGH") &&
      (isVaultOpen || !VAULT_BUCKETS.includes(t.bucket))
  );

  const todayEvents = events.filter((e) => isToday(e.start));
  const upcomingEvents = events.filter((e) => !isToday(e.start)).slice(0, 5);

  async function toggleTodo(todo: TodoSyncDto) {
    const updated = { ...todo, isDone: !todo.isDone, updatedAt: Date.now() };
    setTodos((prev) => prev.map((t) => (t.id === todo.id ? updated : t)));
    await fetch("/api/drive/todos", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ todo: updated }),
    });
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="flex items-center gap-3 text-[#938F99]">
          <svg className="w-5 h-5 animate-spin" fill="none" viewBox="0 0 24 24">
            <circle
              className="opacity-25"
              cx="12"
              cy="12"
              r="10"
              stroke="currentColor"
              strokeWidth="4"
            />
            <path
              className="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
            />
          </svg>
          Loading your brain...
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-center">
          <p className="text-[#F2B8B5] mb-3">{error}</p>
          <button
            onClick={fetchData}
            className="px-4 py-2 bg-[#6750A4] text-white rounded-lg hover:bg-[#7965AF] transition-colors"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  const today = new Date().toLocaleDateString("en-AU", {
    weekday: "long",
    year: "numeric",
    month: "long",
    day: "numeric",
  });

  return (
    <div className="max-w-4xl space-y-6">
      {/* Header */}
      <div>
        <p className="text-sm text-[#938F99] mb-1">{today}</p>
        <h1 className="text-2xl font-bold text-[#E6E1E5]">Good day, Carl</h1>
      </div>

      {/* AI Briefing */}
      <div className="bg-[#2B2930] rounded-2xl p-5 border border-[#6750A4]/30">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-6 h-6 rounded-full bg-[#6750A4] flex items-center justify-center">
            <svg
              className="w-3.5 h-3.5 text-white"
              fill="currentColor"
              viewBox="0 0 24 24"
            >
              <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z" />
            </svg>
          </div>
          <span className="text-sm font-semibold text-[#D0BCFF]">
            Daily Briefing
          </span>
        </div>
        {briefingLoading && !briefing ? (
          <div className="flex items-center gap-2 text-[#938F99]">
            <svg
              className="w-4 h-4 animate-spin"
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              />
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
              />
            </svg>
            <span className="text-sm">Generating briefing...</span>
          </div>
        ) : (
          <p className="text-[#E6E1E5] leading-relaxed">
            {briefing || "No briefing available."}
          </p>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Today's events */}
        <div className="bg-[#2B2930] rounded-2xl p-5 border border-[#49454F]">
          <div className="flex items-center justify-between mb-4">
            <h2 className="font-semibold text-[#E6E1E5]">Today</h2>
            <Link
              href="/calendar"
              className="text-xs text-[#6750A4] hover:text-[#D0BCFF] transition-colors"
            >
              View all →
            </Link>
          </div>
          {todayEvents.length === 0 ? (
            <p className="text-sm text-[#938F99]">No events today</p>
          ) : (
            <div className="space-y-2">
              {todayEvents.map((event) => (
                <div
                  key={event.id}
                  className="flex items-start gap-3 p-2.5 bg-[#1C1B1F] rounded-xl"
                >
                  <div className="w-1 h-full min-h-[40px] bg-[#6750A4] rounded-full flex-shrink-0" />
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-[#E6E1E5] truncate">
                      {event.title}
                    </p>
                    <p className="text-xs text-[#938F99]">
                      {formatEventTime(event)}
                      {event.location && ` · ${event.location}`}
                    </p>
                  </div>
                </div>
              ))}
            </div>
          )}

          {upcomingEvents.length > 0 && (
            <>
              <h3 className="text-xs font-medium text-[#938F99] uppercase tracking-wider mt-4 mb-2">
                Upcoming
              </h3>
              <div className="space-y-1.5">
                {upcomingEvents.map((event) => (
                  <div
                    key={event.id}
                    className="flex items-center justify-between text-sm py-1"
                  >
                    <span className="text-[#CAC4D0] truncate mr-2">
                      {event.title}
                    </span>
                    <span className="text-xs text-[#938F99] flex-shrink-0">
                      {formatEventDate(event.start)}
                    </span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>

        {/* Priority todos */}
        <div className="bg-[#2B2930] rounded-2xl p-5 border border-[#49454F]">
          <div className="flex items-center justify-between mb-4">
            <h2 className="font-semibold text-[#E6E1E5]">Priority Items</h2>
            <Link
              href="/todos"
              className="text-xs text-[#6750A4] hover:text-[#D0BCFF] transition-colors"
            >
              View all →
            </Link>
          </div>
          {visibleTodos.length === 0 ? (
            <p className="text-sm text-[#938F99]">
              No urgent or high priority items
            </p>
          ) : (
            <div className="space-y-2">
              {[...visibleTodos]
                .sort(
                  (a, b) =>
                    PRIORITY_ORDER.indexOf(a.priority) -
                    PRIORITY_ORDER.indexOf(b.priority)
                )
                .map((todo) => (
                  <div
                    key={todo.id}
                    className="flex items-start gap-3 p-2.5 bg-[#1C1B1F] rounded-xl group"
                  >
                    <button
                      onClick={() => toggleTodo(todo)}
                      className="mt-0.5 w-5 h-5 rounded-full border-2 border-[#49454F] flex-shrink-0 group-hover:border-[#6750A4] transition-colors"
                      aria-label="Toggle todo"
                    />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm text-[#E6E1E5] truncate">
                        {todo.title}
                      </p>
                      <div className="flex items-center gap-2 mt-0.5">
                        <span
                          className={`text-xs px-1.5 py-0.5 rounded border ${PRIORITY_COLORS[todo.priority]}`}
                        >
                          {todo.priority}
                        </span>
                        <span className="text-xs text-[#938F99]">
                          {todo.bucket}
                        </span>
                        {todo.dueDate && (
                          <span className="text-xs text-[#938F99]">
                            Due{" "}
                            {new Date(todo.dueDate).toLocaleDateString("en-AU")}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>
                ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
