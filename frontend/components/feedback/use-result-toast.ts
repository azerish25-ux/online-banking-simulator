import * as React from "react";
import { useToast } from "./toast";

/**
 * The one owner for react-query result → toast feedback. Every mutation that
 * must speak when it settles (and the one query whose load failure toasts)
 * wires through here, so changing how an error surfaces - copy, tone, or
 * where it renders - is one edit, not seven features.
 *
 * Firing is transition-based: an effect observes the result's status and
 * reacts only when it *changes* to success/error, so a re-render that swaps
 * `data` or `error` identities (a refetch, a fresh callback closure) can
 * never re-fire a toast. The latest result, feedback config and `push` are
 * kept in refs refreshed every render, and the observer keys on the status
 * transition alone - the same discipline the dialog extraction had to learn
 * the hard way when a post-deposit refetch replayed a success toast.
 *
 * Errors render two ways from the same settle: a corner toast (page-level
 * mutations) or, via `error: false` + `onFailure`, inline inside a dialog
 * that stays open with the offending value still in its field.
 */

export type ResultToastTone = "success" | "error" | "info";

/** What a toast says, and how it reads (default tones: success "success", error "error"). */
export type ResultToastSpec = { message: string; tone?: ResultToastTone };

/** Feedback for one async result: what to toast when it settles, what to run. */
export type ResultToastFeedback<TData, TError extends Error> = {
  /**
   * What to toast when the result fails. Defaults to the error's message in
   * "error" tone. Pass `false` to suppress the corner toast - for dialog
   * surfaces that render the rejection inline instead.
   */
  error?: ResultToastSpec | ((error: TError) => ResultToastSpec) | false;
  /**
   * Hears every failed attempt's message, exactly once. Dialog surfaces pair
   * this with `error: false` and render the message inline next to the field
   * that failed (a bank keeps the rejection beside the input, not in a corner
   * toast behind a scrim). The settled error rides along as the second
   * argument so an inline surface can re-classify the failure (e.g. truthfully
   * say an interrupted money operation is saved for a safe retry) without
   * re-deriving it from raw copy.
   */
  onFailure?: (message: string, error: TError) => void;
  /** Fired exactly once per success: the toast first, then `run`. */
  success?: {
    toast?: ResultToastSpec | ((data: TData) => ResultToastSpec);
    /** Extra side effects after the toast (close a dialog, reset a form...). */
    run?: (data: TData) => void;
  };
};

/** The slice of a react-query mutation/query result this observer needs. */
type AsyncResult<TData, TError extends Error> = {
  status: "idle" | "pending" | "success" | "error";
  error: TError | null;
  data: TData | undefined;
};

export function useResultToast<TData, TError extends Error = Error>(
  result: AsyncResult<TData, TError>,
  feedback?: ResultToastFeedback<TData, TError>
): void {
  const { push } = useToast();

  // Latest values refreshed every render; the observer below must not key on
  // them (identity churns), so it reads through refs at fire time.
  const latest = React.useRef({ result, feedback, push });
  React.useEffect(() => {
    latest.current = { result, feedback, push };
  });

  const prevStatus = React.useRef<AsyncResult<TData, TError>["status"]>(result.status);
  React.useEffect(() => {
    const { result: settled, feedback: fb, push: toast } = latest.current;
    const prev = prevStatus.current;
    prevStatus.current = settled.status;
    if (prev === settled.status) return; // not a settle - nothing new to say

    if (settled.status === "error") {
      const err = fb?.error;
      const failed = settled.error;
      // The message the surface would have toasted - inline surfaces hear the
      // same words through onFailure, so copy never drifts between the two.
      let message: string | undefined;
      if (err === undefined || err === false) {
        message = failed?.message;
        if (err === undefined && message) toast(message, "error");
      } else {
        const spec = typeof err === "function" ? err(failed as TError) : err;
        message = spec.message;
        toast(spec.message, spec.tone ?? "error");
      }
      if (message && fb?.onFailure && settled.error) {
        fb.onFailure(message, settled.error);
      }
    } else if (settled.status === "success" && fb?.success) {
      const { toast: toastSpec, run } = fb.success;
      if (toastSpec) {
        const spec = typeof toastSpec === "function" ? toastSpec(settled.data as TData) : toastSpec;
        toast(spec.message, spec.tone ?? "success");
      }
      run?.(settled.data as TData);
    }
    // Deps: only the status transition can settle a result; data/error are
    // read through refs, so a refetch that swaps them never re-fires.
  }, [result.status]);
}
