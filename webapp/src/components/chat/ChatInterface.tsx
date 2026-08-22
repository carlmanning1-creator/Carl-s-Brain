"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import type { ChatMessage } from "@/lib/types";
import type { ChatThreadDto } from "@/lib/fileFormat";

function MessageBubble({ message }: { message: ChatMessage }) {
  const isUser = message.role === "user";

  // Render markdown-ish content (basic)
  const renderContent = (text: string) => {
    return text.split("\n").map((line, i) => (
      <span key={i}>
        {line}
        {i < text.split("\n").length - 1 && <br />}
      </span>
    ));
  };

  return (
    <div
      className={`flex items-start gap-3 ${isUser ? "flex-row-reverse" : ""}`}
    >
      {/* Avatar */}
      <div
        className={`w-8 h-8 rounded-full flex-shrink-0 flex items-center justify-center text-sm font-medium ${
          isUser ? "bg-[#6750A4] text-white" : "bg-[#49454F] text-[#CAC4D0]"
        }`}
      >
        {isUser ? "C" : "AI"}
      </div>

      {/* Bubble */}
      <div
        className={`max-w-[75%] px-4 py-3 rounded-2xl text-sm leading-relaxed ${
          isUser
            ? "bg-[#6750A4] text-white rounded-tr-sm"
            : "bg-[#2B2930] text-[#E6E1E5] border border-[#49454F] rounded-tl-sm"
        }`}
      >
        {renderContent(message.content)}
      </div>
    </div>
  );
}

function TypingIndicator() {
  return (
    <div className="flex items-start gap-3">
      <div className="w-8 h-8 rounded-full bg-[#49454F] flex items-center justify-center text-sm font-medium text-[#CAC4D0]">
        AI
      </div>
      <div className="bg-[#2B2930] border border-[#49454F] rounded-2xl rounded-tl-sm px-4 py-3">
        <div className="flex items-center gap-1.5">
          <span
            className="w-1.5 h-1.5 rounded-full bg-[#938F99] animate-bounce"
            style={{ animationDelay: "0ms" }}
          />
          <span
            className="w-1.5 h-1.5 rounded-full bg-[#938F99] animate-bounce"
            style={{ animationDelay: "150ms" }}
          />
          <span
            className="w-1.5 h-1.5 rounded-full bg-[#938F99] animate-bounce"
            style={{ animationDelay: "300ms" }}
          />
        </div>
      </div>
    </div>
  );
}

export default function ChatInterface() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [streamingContent, setStreamingContent] = useState("");
  const [error, setError] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // ── Synced conversations ──────────────────────────────────────────────────
  //
  // The same threads the phone shows, read from and written back to Drive, so a conversation
  // started on the phone can be picked up here and the other way round. The whole thread is
  // saved each time rather than a delta: message ids are per-device and mean nothing across
  // the two clients, so the file is the conversation.
  const [threads, setThreads] = useState<ChatThreadDto[]>([]);
  const [threadId, setThreadId] = useState<number | null>(null);
  const [threadCreatedAt, setThreadCreatedAt] = useState<number | null>(null);
  const [loadingThreads, setLoadingThreads] = useState(true);

  const refreshThreads = useCallback(async () => {
    try {
      const res = await fetch("/api/drive/chat");
      if (!res.ok) return;
      const data = await res.json();
      setThreads(data.threads ?? []);
    } catch {
      // A conversation list that cannot load must not stop Carl having a conversation.
    } finally {
      setLoadingThreads(false);
    }
  }, []);

  useEffect(() => {
    refreshThreads();
  }, [refreshThreads]);

  function openThread(thread: ChatThreadDto) {
    setThreadId(thread.id);
    setThreadCreatedAt(thread.createdAt);
    setMessages(
      thread.messages.map((m) => ({
        role: m.isFromUser ? "user" : "assistant",
        content: m.content,
      }))
    );
    setError(null);
  }

  function newThread() {
    setThreadId(null);
    setThreadCreatedAt(null);
    setMessages([]);
    setError(null);
  }

  /**
   * Publishes the conversation after each exchange.
   *
   * Failure is deliberately silent in the transcript: the reply is already on screen and
   * useful, and an error banner over a working answer would read as the answer having failed.
   * The next exchange retries the whole thread, so one dropped save costs nothing.
   */
  async function persistThread(all: ChatMessage[]) {
    if (all.length === 0) return;
    try {
      const res = await fetch("/api/drive/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id: threadId ?? undefined,
          createdAt: threadCreatedAt ?? undefined,
          messages: all.map((m) => ({
            content: m.content,
            isFromUser: m.role === "user",
          })),
        }),
      });
      if (!res.ok) return;
      const data = await res.json();
      if (typeof data.id === "number") {
        setThreadId(data.id);
        setThreadCreatedAt((prev) => prev ?? data.id);
      }
      refreshThreads();
    } catch {
      // See above — a failed save is retried by the next message.
    }
  }

  async function deleteThread(id: number) {
    if (!confirm("Delete this conversation? It goes from the phone too.")) return;
    await fetch(`/api/drive/chat?id=${id}`, { method: "DELETE" });
    if (id === threadId) newThread();
    refreshThreads();
  }

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streamingContent]);

  function autoResize() {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, 160)}px`;
  }

  async function sendMessage() {
    const text = input.trim();
    if (!text || streaming) return;

    setInput("");
    setError(null);
    if (textareaRef.current) textareaRef.current.style.height = "auto";

    const newMessages: ChatMessage[] = [
      ...messages,
      { role: "user", content: text },
    ];
    setMessages(newMessages);
    setStreaming(true);
    setStreamingContent("");

    try {
      const res = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: newMessages }),
      });

      if (!res.ok) {
        const data = await res.json();
        throw new Error(
          data.error || "Failed to get response"
        );
      }

      const reader = res.body?.getReader();
      const decoder = new TextDecoder();
      let fullContent = "";

      if (reader) {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          const chunk = decoder.decode(value, { stream: true });
          fullContent += chunk;
          setStreamingContent(fullContent);
        }
      }

      const withReply: ChatMessage[] = [
        ...newMessages,
        { role: "assistant", content: fullContent },
      ];
      setMessages(withReply);
      persistThread(withReply);
    } catch (err) {
      const message =
        err instanceof Error ? err.message : "Something went wrong";
      setError(message);
    } finally {
      setStreaming(false);
      setStreamingContent("");
    }
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  }

  /**
   * Starts a new conversation rather than erasing the current one.
   *
   * "Clear chat" used to throw the transcript away. Now that threads are saved to Drive and
   * visible on the phone, silently destroying one would be a real loss — so this only steps
   * away from it, and deleting is an explicit action in the list.
   */
  function clearChat() {
    if (messages.length === 0) return;
    newThread();
  }

  return (
    <div className="flex flex-col h-[calc(100vh-4rem)]">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-2xl font-bold text-[#E6E1E5]">Chat</h1>
          <p className="text-sm text-[#938F99]">
            Claude has access to your memory context
          </p>
        </div>
        {messages.length > 0 && (
          <button
            onClick={clearChat}
            className="text-sm text-[#938F99] hover:text-[#CAC4D0] transition-colors"
          >
            New conversation
          </button>
        )}
      </div>

      {/* Saved conversations — the same threads the phone shows. */}
      {!loadingThreads && threads.length > 0 && (
        <div className="flex gap-2 overflow-x-auto pb-3 mb-1">
          {threads.map((t) => (
            <div
              key={t.id}
              className={`group flex items-center gap-1 flex-shrink-0 rounded-xl border text-xs transition-colors ${
                t.id === threadId
                  ? "bg-[#6750A4]/20 border-[#6750A4] text-[#E6E1E5]"
                  : "bg-[#2B2930] border-[#49454F] text-[#CAC4D0] hover:border-[#938F99]"
              }`}
            >
              <button
                onClick={() => openThread(t)}
                className="px-3 py-1.5 max-w-[14rem] truncate text-left"
                title={t.title}
              >
                {t.title || "Untitled"}
              </button>
              <button
                onClick={() => deleteThread(t.id)}
                aria-label={`Delete ${t.title}`}
                className="pr-2 text-[#938F99] opacity-0 group-hover:opacity-100 hover:text-red-400 transition-opacity"
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Messages */}
      <div className="flex-1 bg-[#2B2930] rounded-2xl border border-[#49454F] overflow-y-auto p-4 space-y-4 mb-4">
        {messages.length === 0 && !streaming ? (
          <div className="flex flex-col items-center justify-center h-full text-center">
            <div className="w-16 h-16 rounded-2xl bg-[#6750A4]/20 flex items-center justify-center mb-4">
              <svg
                className="w-8 h-8 text-[#6750A4]"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z"
                />
              </svg>
            </div>
            <p className="text-[#CAC4D0] font-medium mb-1">
              Ask me anything
            </p>
            <p className="text-sm text-[#938F99] max-w-sm">
              I have your memory context loaded. Ask me to help with tasks,
              recall notes, plan your day, or just chat.
            </p>
          </div>
        ) : (
          <>
            {messages.map((msg, i) => (
              <MessageBubble key={i} message={msg} />
            ))}
            {streaming &&
              (streamingContent ? (
                <MessageBubble
                  message={{ role: "assistant", content: streamingContent }}
                />
              ) : (
                <TypingIndicator />
              ))}
          </>
        )}
        {error && (
          <div className="bg-red-400/10 border border-red-400/20 rounded-xl p-3">
            <p className="text-sm text-red-400">{error}</p>
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div className="flex items-end gap-3 bg-[#2B2930] border border-[#49454F] rounded-2xl p-3">
        <textarea
          ref={textareaRef}
          value={input}
          onChange={(e) => {
            setInput(e.target.value);
            autoResize();
          }}
          onKeyDown={handleKeyDown}
          placeholder="Message Claude... (Enter to send, Shift+Enter for newline)"
          rows={1}
          className="flex-1 bg-transparent text-[#E6E1E5] placeholder-[#938F99] resize-none focus:outline-none text-sm leading-relaxed"
          style={{ maxHeight: "160px" }}
          disabled={streaming}
        />
        <button
          onClick={sendMessage}
          disabled={!input.trim() || streaming}
          className="w-9 h-9 rounded-xl bg-[#6750A4] text-white flex items-center justify-center flex-shrink-0 hover:bg-[#7965AF] disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          aria-label="Send message"
        >
          {streaming ? (
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
          ) : (
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
                d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8"
              />
            </svg>
          )}
        </button>
      </div>
    </div>
  );
}
