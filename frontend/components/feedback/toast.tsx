"use client";

import * as React from "react";
import { X } from "lucide-react";
import { cn } from "../../lib/cn";

type Toast = { id: number; title: string; tone: "success" | "error" | "info" };

const ToastContext = React.createContext<{ push: (title: string, tone?: Toast["tone"]) => void }>({
  push: () => {}
});

export function useToast() {
  return React.useContext(ToastContext);
}

let nextId = 1;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = React.useState<Toast[]>([]);

  const dismiss = React.useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = React.useCallback((title: string, tone: Toast["tone"] = "info") => {
    const id = nextId++;
    setToasts((prev) => [...prev, { id, title, tone }]);
    // Auto-dismiss, but the per-toast close button lets the user move on now.
    window.setTimeout(() => dismiss(id), 4000);
  }, [dismiss]);

  return (
    <ToastContext.Provider value={{ push }}>
      {children}
      {/* One polite live region for every toast that arrives while the user is
          elsewhere; each toast also carries role="status" for focused readers. */}
      <div aria-live="polite" className="fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            role="status"
            className={cn(
              "panel relative pr-10 text-sm motion-safe:animate-toast-in",
              "px-4 py-3",
              t.tone === "success" && "border-success-border",
              t.tone === "error" && "border-danger-border"
            )}
          >
            {t.title}
            <button
              type="button"
              onClick={() => dismiss(t.id)}
              aria-label="Dismiss notification"
              className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded-md p-1.5 text-content-muted hover:bg-ink-700 hover:text-content"
            >
              <X size={14} aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
