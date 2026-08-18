"use client";

import { useEffect, useState, useCallback } from "react";
import { useVault } from "@/hooks/useVault";
import { DEFAULT_BUCKETS } from "@/lib/types";
import type { TodoSyncDto } from "@/lib/types";
import TodoEditor from "./TodoEditor";

const PRIORITY_ORDER: TodoSyncDto["priority"][] = [
  "URGENT",
  "HIGH",
  "NORMAL",
  "SOMEDAY",
];

const PRIORITY_COLORS: Record<string, string> = {
  URGENT: "text-red-400 bg-red-400/10 border-red-400/20",
  HIGH: "text-orange-400 bg-orange-400/10 border-orange-400/20",
  NORMAL: "text-blue-400 bg-blue-400/10 border-blue-400/20",
  SOMEDAY: "text-[#938F99] bg-[#938F99]/10 border-[#938F99]/20",
};

type FilterView = "active" | "done" | "all";

export default function TodosList() {
  const { isVaultOpen } = useVault();
  const [todos, setTodos] = useState<TodoSyncDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedBucket, setSelectedBucket] = useState("All");
  const [filterView, setFilterView] = useState<FilterView>("active");
  const [sortMode, setSortMode] = useState<"priority" | "due" | "created" | "alpha">("priority");
  const [editingTodo, setEditingTodo] = useState<TodoSyncDto | null | undefined>(undefined);
  const [showEditor, setShowEditor] = useState(false);
  /** How many to-dos the server withheld — shown as a count, never as content. */
  const [hiddenVaultCount, setHiddenVaultCount] = useState(0);

  // Vault state is sent to the server, which filters before responding. The client no longer
  // receives vault to-dos at all while locked, so there is nothing to leak in devtools or in
  // memory on a shared machine. Refetches when the vault is unlocked or locked.
  const fetchTodos = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(
        `/api/drive/todos${isVaultOpen ? "?vault=open" : ""}`
      );
      if (!res.ok) throw new Error("Failed to fetch");
      const data = await res.json();
      setTodos(data.todos ?? []);
      setHiddenVaultCount(data.hiddenCount ?? 0);
    } catch {
      setError("Failed to load todos.");
    } finally {
      setLoading(false);
    }
  }, [isVaultOpen]);

  useEffect(() => {
    fetchTodos();
  }, [fetchTodos]);

  const visibleBuckets = DEFAULT_BUCKETS.filter(
    (b) => !b.isVault || isVaultOpen
  );

  const filteredTodos = todos
    .filter((t) => {
      // No vault check here any more — the server already withheld them. Keeping a
      // client-side filter as well would imply the data is present and merely hidden.
      if (selectedBucket !== "All" && t.bucket !== selectedBucket) return false;
      if (filterView === "active") return !t.isDone;
      if (filterView === "done") return t.isDone;
      return true;
    })
    .sort((a, b) => {
      // Done items go last regardless of sort mode
      if (a.isDone !== b.isDone) return a.isDone ? 1 : -1;
      // Sort by selected mode
      if (sortMode === "alpha") return a.title.localeCompare(b.title);
      if (sortMode === "created") return (b.createdAt ?? 0) - (a.createdAt ?? 0);
      if (sortMode === "due") {
        const aDue = a.dueDate ?? Infinity;
        const bDue = b.dueDate ?? Infinity;
        if (aDue !== bDue) return aDue - bDue;
        return PRIORITY_ORDER.indexOf(a.priority) - PRIORITY_ORDER.indexOf(b.priority);
      }
      // default: priority then due date
      const priorityDiff = PRIORITY_ORDER.indexOf(a.priority) - PRIORITY_ORDER.indexOf(b.priority);
      if (priorityDiff !== 0) return priorityDiff;
      return (a.dueDate ?? Infinity) - (b.dueDate ?? Infinity);
    });

  async function toggleTodo(todo: TodoSyncDto) {
    const updated = { ...todo, isDone: !todo.isDone, updatedAt: Date.now() };
    setTodos((prev) => prev.map((t) => (t.id === todo.id ? updated : t)));
    await fetch("/api/drive/todos", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ todo: updated }),
    });
  }

  async function saveTodo(todoData: TodoSyncDto): Promise<boolean> {
    try {
      const res = await fetch("/api/drive/todos", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ todo: todoData }),
      });
      if (!res.ok) throw new Error("Save failed");
      const data = await res.json();
      setTodos((prev) => {
        const existing = prev.findIndex((t) => t.id === data.todo.id);
        if (existing >= 0) {
          const next = [...prev];
          next[existing] = data.todo;
          return next;
        }
        return [...prev, data.todo];
      });
      return true;
    } catch {
      return false;
    }
  }

  async function deleteTodo(todo: TodoSyncDto) {
    if (!confirm(`Delete "${todo.title}"?`)) return;
    const deleted = { ...todo, deletedAt: Date.now(), updatedAt: Date.now() };
    await fetch("/api/drive/todos", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ todo: deleted }),
    });
    setTodos((prev) => prev.filter((t) => t.id !== todo.id));
  }

  // `todos` already excludes vault items while locked, so no second filter is needed.
  const activeCounts = todos.filter((t) => !t.isDone).length;

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-[#E6E1E5]">Todos</h1>
          {activeCounts > 0 && (
            <p className="text-sm text-[#938F99]">
              {activeCounts} active item{activeCounts !== 1 ? "s" : ""}
            </p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={fetchTodos}
            disabled={loading}
            className="p-2 text-[#938F99] hover:text-[#E6E1E5] disabled:opacity-40 transition-colors"
            title="Refresh"
          >
            <svg className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </button>
          <button
            onClick={() => {
              setEditingTodo(null);
              setShowEditor(true);
            }}
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
            New Todo
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-3">
        {/* View filter */}
        <div className="flex bg-[#2B2930] border border-[#49454F] rounded-xl p-1 gap-1">
          {(["active", "done", "all"] as FilterView[]).map((v) => (
            <button
              key={v}
              onClick={() => setFilterView(v)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium transition-colors capitalize ${
                filterView === v
                  ? "bg-[#6750A4] text-white"
                  : "text-[#938F99] hover:text-[#CAC4D0]"
              }`}
            >
              {v}
            </button>
          ))}
        </div>

        <select
          value={selectedBucket}
          onChange={(e) => setSelectedBucket(e.target.value)}
          className="px-3 py-2 bg-[#2B2930] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
        >
          <option value="All">All Buckets</option>
          {visibleBuckets.map((b) => (
            <option key={b.name} value={b.name}>
              {b.name}
            </option>
          ))}
        </select>
        <select
          value={sortMode}
          onChange={(e) => setSortMode(e.target.value as "priority" | "due" | "created" | "alpha")}
          className="px-3 py-2 bg-[#2B2930] border border-[#49454F] rounded-xl text-[#CAC4D0] focus:outline-none focus:border-[#6750A4]"
        >
          <option value="priority">Priority</option>
          <option value="due">Due Date</option>
          <option value="created">Created</option>
          <option value="alpha">A–Z</option>
        </select>
      </div>

      {/* Todo list */}
      {loading ? (
        <div className="flex items-center gap-3 py-12 justify-center text-[#938F99]">
          <svg
            className="w-5 h-5 animate-spin"
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
          Loading todos...
        </div>
      ) : error ? (
        <div className="text-center py-12">
          <p className="text-[#F2B8B5] mb-3">{error}</p>
          <button
            onClick={fetchTodos}
            className="px-4 py-2 bg-[#6750A4] text-white rounded-lg hover:bg-[#7965AF] transition-colors"
          >
            Retry
          </button>
        </div>
      ) : filteredTodos.length === 0 ? (
        <div className="text-center py-16">
          <div className="w-16 h-16 rounded-2xl bg-[#2B2930] flex items-center justify-center mx-auto mb-4">
            <svg
              className="w-8 h-8 text-[#49454F]"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1.5}
                d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
              />
            </svg>
          </div>
          <p className="text-[#938F99]">
            {filterView === "done"
              ? "No completed todos"
              : "No todos yet. Add your first one!"}
          </p>
        </div>
      ) : (
        <div className="space-y-2">
          {filteredTodos.map((todo) => (
            <div
              key={todo.id}
              className={`flex items-start gap-3 p-3.5 bg-[#2B2930] border rounded-xl group transition-all ${
                todo.isDone ? "border-[#49454F]/30 opacity-60" : "border-[#49454F] hover:border-[#6750A4]/30"
              }`}
            >
              {/* Checkbox */}
              <button
                onClick={() => toggleTodo(todo)}
                className={`mt-0.5 w-5 h-5 rounded-full border-2 flex-shrink-0 flex items-center justify-center transition-colors ${
                  todo.isDone
                    ? "bg-[#6750A4] border-[#6750A4]"
                    : "border-[#49454F] hover:border-[#6750A4]"
                }`}
                aria-label="Toggle todo"
              >
                {todo.isDone && (
                  <svg
                    className="w-3 h-3 text-white"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={3}
                      d="M5 13l4 4L19 7"
                    />
                  </svg>
                )}
              </button>

              {/* Content */}
              <div className="flex-1 min-w-0">
                <p
                  className={`text-sm font-medium ${
                    todo.isDone
                      ? "text-[#938F99] line-through"
                      : "text-[#E6E1E5]"
                  }`}
                >
                  {todo.title}
                </p>
                <div className="flex items-center gap-2 mt-1">
                  <span
                    className={`text-xs px-1.5 py-0.5 rounded border ${PRIORITY_COLORS[todo.priority]}`}
                  >
                    {todo.priority}
                  </span>
                  <span className="text-xs text-[#938F99]">{todo.bucket}</span>
                  {todo.dueDate && (
                    <span
                      className={`text-xs ${
                        todo.dueDate < Date.now() && !todo.isDone
                          ? "text-red-400"
                          : "text-[#938F99]"
                      }`}
                    >
                      Due {new Date(todo.dueDate).toLocaleDateString("en-AU")}
                    </span>
                  )}
                </div>
              </div>

              {/* Actions */}
              <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                <button
                  onClick={() => {
                    setEditingTodo(todo);
                    setShowEditor(true);
                  }}
                  className="p-1.5 text-[#938F99] hover:text-[#CAC4D0] hover:bg-[#49454F]/40 rounded-lg transition-colors"
                  title="Edit"
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
                      d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                    />
                  </svg>
                </button>
                <button
                  onClick={() => deleteTodo(todo)}
                  className="p-1.5 text-[#938F99] hover:text-[#F2B8B5] hover:bg-red-400/10 rounded-lg transition-colors"
                  title="Delete"
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
                      d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                    />
                  </svg>
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Todo Editor Modal */}
      {showEditor && (
        <TodoEditor
          todo={editingTodo ?? null}
          onSave={saveTodo}
          onClose={() => {
            setShowEditor(false);
            setEditingTodo(undefined);
          }}
        />
      )}
    </div>
  );
}
