"use client";

import * as React from "react";
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

  const push = React.useCallback((title: string, tone: Toast["tone"] = "info") => {
    const id = nextId++;
    setToasts((prev) => [...prev, { id, title, tone }]);
    window.setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), 4000);
  }, []);

  return (
    <ToastContext.Provider value={{ push }}>
      {children}
      <div aria-live="polite" className="fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            role="status"
            className={cn(
              "panel px-4 py-3 text-sm",
              t.tone === "success" && "border-emerald-800",
              t.tone === "error" && "border-red-800"
            )}
          >
            {t.title}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
