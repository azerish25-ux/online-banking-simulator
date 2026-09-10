import { ApiError } from "./api";

/**
 * The one owner of "did the server reject this, or is the outcome unknown?"
 * Every money surface: the interrupted-operation copy below, the key-lifecycle
 * rules in queries.ts, the operator reversal dialog: reads the rule from here,
 * so the definitive-vs-ambiguous boundary can never drift between them.
 *
 * A definitive rejection (4xx except 409/429: validation, insufficient
 * funds, ...) proves the server recorded NOTHING, so the plain server message is
 * the truth. Everything else: a network drop (no HTTP response at all), 5xx,
 * 429, or a 409 idempotency conflict: means the outcome is genuinely UNKNOWN:
 * the server may have committed.
 */
export function isDefinitiveRejection(err: unknown): err is ApiError {
  return err instanceof ApiError && err.status >= 400 && err.status < 500
      && err.status !== 409 && err.status !== 429;
}

/**
 * A 2xx response whose body failed validation is an UNKNOWN outcome, never a
 * completion: the server may have committed and answered garbage (a proxy,
 * a captive portal, a broken codec). The operation record and its key must
 * survive exactly as they would for a network drop.
 */
export const MALFORMED_SUCCESS_TITLE = "Malformed Success";

export function malformedSuccessError(kind: "deposit" | "transfer"): ApiError {
  const what = kind === "deposit" ? "deposit" : "transfer";
  return new ApiError(0, MALFORMED_SUCCESS_TITLE,
      "The server answered with an unreadable " + what + " response, so we can't confirm "
      + "whether it went through. The attempt is saved. Retry to check; it can never double-post.");
}

export function isMalformedSuccess(err: unknown): err is ApiError {
  return err instanceof ApiError && err.status === 0 && err.title === MALFORMED_SUCCESS_TITLE;
}

/**
 * Statuses that mean the server never PROCESSED the replayed check itself
 * (authentication, authorization, routing, timeout). They say nothing about
 * whether an earlier ambiguous attempt committed, so the record stays.
 * A 400/422 business rejection is different: the server judged the identical
 * payload and refused it, which proves nothing was recorded for the key.
 *
 * Scope: REPLAY CHECKS only (resolveUnresolvedOperation). A FRESH submission
 * receiving one of these was refused before processing a key this client
 * minted moments ago: there classifyMoneyFailure is right to call it
 * definitive, because no earlier attempt exists to be unknown about.
 */
const REPLAY_INCONCLUSIVE_STATUSES = new Set([401, 403, 404, 408]);

export function isReplayInconclusive(err: unknown): err is ApiError {
  return err instanceof ApiError && REPLAY_INCONCLUSIVE_STATUSES.has(err.status);
}

export function replayInconclusiveFailure(kind: "deposit" | "transfer"): MoneyFailure {
  const what = kind === "deposit" ? "deposit" : "transfer";
  return {
    ambiguous: true,
    message:
      "The server refused to process this check, so the outcome of your earlier " + what
      + " attempt is still unknown. The attempt is saved. Sign in again and retry to check; "
      + "it can never double-post."
  };
}

export type MoneyFailure = {
  message: string;
  /** True when the server may have committed: a retry is the safe check. */
  ambiguous: boolean;
};

/**
 * Truthful copy for a failed deposit/transfer (interrupted-response UX).
 *
 * The danger after an interrupted money operation is a user who does not know
 * whether it went through and acts on a guess. A definitive rejection proves
 * the server recorded nothing. An ambiguous outcome must say so, and must say
 * the attempt is saved so a retry checks the server (same idempotency key,
 * never a double post) instead of inviting the user to guess by looking at
 * their balance.
 */
export function classifyMoneyFailure(
  kind: "deposit" | "transfer",
  err: unknown
): MoneyFailure {
  const what = kind === "deposit" ? "deposit" : "transfer";
  if (err instanceof ApiError) {
    if (err.status === 409) {
      return {
        ambiguous: true,
        message:
          "This " + what + " was submitted before under the same attempt id, so it may already have gone through. "
          + "Submitting it again fetches the original result and can never move money twice."
      };
    }
    if (err.status === 429) {
      return {
        ambiguous: true,
        message:
          "Too many attempts right now. Nothing was lost. Wait a moment and retry; "
          + "the retry cannot double-post."
      };
    }
    if (err.status >= 500) {
      return {
        ambiguous: true,
        message:
          "The server could not confirm whether your " + what + " went through. "
          + "The attempt is saved. Retry to check; it can only post once."
      };
    }
  }
  if (isDefinitiveRejection(err)) {
    // A definitive rejection (validation, insufficient funds, ...): the server
    // recorded nothing, so the server's own words are the whole truth.
    return { ambiguous: false, message: err.message };
  }
  // No HTTP response at all (network drop, timeout, killed tab): unknown.
  return {
    ambiguous: true,
    message:
      "We couldn't reach the server, so we can't confirm whether your " + what + " went through. "
      + "The attempt is saved. Retry to check; it can never double-post."
  };
}

/**
 * Truthful copy for a failed operator reversal (V29). Reversal is NOT
 * idempotent like a keyed deposit/transfer: a second reversal of the same
 * transaction is refused: so an ambiguous failure must point the operator at
 * the posted list to learn the true state, never at a blind retry.
 */
export function classifyReversalFailure(err: unknown): MoneyFailure {
  if (isDefinitiveRejection(err)) {
    // The server refused (already reversed, not posted, reason missing, the
    // payee no longer holds the funds): its words are the truth.
    return { ambiguous: false, message: err.message };
  }
  return {
    ambiguous: true,
    message:
      "The server could not confirm whether the reversal was recorded. "
      + "Refresh the posted list to see the true state before acting again. "
      + "A second reversal of the same transaction would be refused."
  };
}
