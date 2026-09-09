"use client";

import * as React from "react";
import { Button } from "./button";

/**
 * A fetch that failed - never a silent "empty". Rendered where the failure
 * happened (inside the Card whose data is missing) with an explicit retry, so
 * a failed history fetch can never read as "No transactions" and a failed
 * account request never as "Account not found". Distinct from
 * EmptyState on purpose: an error is not the absence of data.
 */
export function LoadFailed({
  title,
  description,
  onRetry
}: {
  title: string;
  description?: string;
  onRetry?: () => void;
}) {
  return (
    <div
      role="alert"
      className="border border-danger-border bg-danger-surface px-4 py-6 text-center"
    >
      <p className="text-base font-semibold text-danger">{title}</p>
      {description ? <p className="muted mx-auto mt-1 max-w-sm text-sm">{description}</p> : null}
      {onRetry ? (
        <Button type="button" variant="secondary" size="sm" className="mt-3" onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </div>
  );
}
