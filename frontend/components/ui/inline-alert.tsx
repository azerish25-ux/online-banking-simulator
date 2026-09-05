import * as React from "react";

/**
 * A dialog-level rejection, rendered where the user is looking: inside the
 * dialog that stayed open, not as a corner toast behind the scrim. Server
 * rejections that concern a single field use `Field`'s inline error instead;
 * this is the alert for failures without one obvious field.
 */
export function InlineAlert({ children }: { children: React.ReactNode }) {
  return (
    <p
      role="alert"
      className="rounded-md border border-danger-border bg-danger-surface px-3 py-2 text-sm text-rose"
    >
      {children}
    </p>
  );
}
