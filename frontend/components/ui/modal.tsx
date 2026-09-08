"use client";

import * as React from "react";
import { X } from "lucide-react";

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Accessible modal dialog: initial focus lands on the close button, Tab /
 * Shift+Tab cycles inside the dialog (a true focus trap - background content
 * can never receive keyboard focus), Escape closes, and focus returns to the
 * element that opened it.
 */
export function Modal({
  open,
  onClose,
  title,
  children
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
}) {
  const dialogRef = React.useRef<HTMLDivElement>(null);
  const closeRef = React.useRef<HTMLButtonElement>(null);
  const opener = React.useRef<Element | null>(null);
  const titleId = React.useId();

  // Latest-callback ref: the keydown listener is registered once per
  // open/close, but must call the CURRENT onClose when it fires. Without the
  // ref, callers that pass inline closures would force the listener (and the
  // focus lifecycle) to restart on every parent render.
  const onCloseRef = React.useRef(onClose);
  onCloseRef.current = onClose;

  // Focus + scroll lifecycle is governed by `open` ALONE - never by the
  // identity of onClose. A dialog that owns a controlled input whose state
  // lives in the page re-renders the parent on each keystroke, which re-
  // creates the inline onClose; if that identity restarted this effect the
  // close button would steal focus after every character. Restore to
  // the actual opener and unlock scroll on close/unmount.
  React.useEffect(() => {
    if (!open) return;
    opener.current = document.activeElement;
    closeRef.current?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
      (opener.current as HTMLElement | null)?.focus?.();
      opener.current = null;
    };
  }, [open]);

  // Trap + Escape: registered while open, reads callbacks through the ref.
  React.useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        onCloseRef.current();
        return;
      }
      if (e.key !== "Tab") return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusables = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (focusables.length === 0) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement;
      const inside = dialog.contains(active);
      if (e.shiftKey) {
        if (!inside || active === first) {
          e.preventDefault();
          last.focus();
        }
      } else if (!inside || active === last) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  if (!open) return null;
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-scrim/60 p-4 motion-safe:animate-overlay-in"
      onClick={onClose}
      role="presentation"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="panel w-full max-w-md rounded-lg p-5 shadow-dialog motion-safe:animate-dialog-in"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 id={titleId} className="text-base font-semibold">{title}</h2>
          <button
            onClick={onClose}
            aria-label="Close dialog"
            ref={closeRef}
            className="rounded px-2 py-1 text-content-secondary hover:bg-surface-subtle hover:text-content"
          >
              <X size={16} aria-hidden="true" />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
