"use client";

import * as React from "react";
import { Button } from "./button";
import { Modal } from "./modal";

/**
 * Consequence dialog for destructive or hard-to-reverse actions. The confirm
 * button is danger-toned; Escape/backdrop/Cancel all abort.
 */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel = "Confirm",
  busy = false,
  onConfirm,
  onClose
}: {
  open: boolean;
  title: string;
  body: React.ReactNode;
  confirmLabel?: string;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Modal open={open} onClose={busy ? () => {} : onClose} title={title}>
      <div className="text-sm leading-relaxed">{body}</div>
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" disabled={busy} onClick={onClose}>
          Cancel
        </Button>
        <Button variant="danger" disabled={busy} onClick={onConfirm}>
          {busy ? "Working..." : confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}
