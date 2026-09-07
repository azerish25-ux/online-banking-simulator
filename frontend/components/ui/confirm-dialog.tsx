"use client";

import * as React from "react";
import { Button } from "./button";
import { Modal } from "./modal";
import { InlineAlert } from "./inline-alert";

/**
 * Consequence dialog for destructive or hard-to-reverse actions. The confirm
 * button is danger-toned; Escape/backdrop/Cancel all abort. When the action
 * fails on the server the rejection renders here, beside the buttons that
 * caused it - never as a corner toast behind the scrim.
 */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel = "Confirm",
  busy = false,
  confirmDisabled = false,
  error = null,
  onConfirm,
  onClose
}: {
  open: boolean;
  title: string;
  body: React.ReactNode;
  confirmLabel?: string;
  busy?: boolean;
  /** Grey the confirm button until a prerequisite (e.g. a mandatory reason) is met. */
  confirmDisabled?: boolean;
  /** A server rejection to show inside the dialog (null clears it). */
  error?: string | null;
  onConfirm: () => void;
  onClose: () => void;
}) {
  return (
    <Modal open={open} onClose={busy ? () => {} : onClose} title={title}>
      <div className="text-sm leading-relaxed">{body}</div>
      {error ? (
        <div className="mt-4">
          <InlineAlert>{error}</InlineAlert>
        </div>
      ) : null}
      <div className="mt-4 flex justify-end gap-2">
        <Button variant="secondary" disabled={busy} onClick={onClose}>
          Cancel
        </Button>
        <Button variant="danger" disabled={busy || confirmDisabled} onClick={onConfirm}>
          {busy ? "Working..." : confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}
