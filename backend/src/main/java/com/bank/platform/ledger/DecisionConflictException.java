package com.bank.platform.ledger;

import java.util.UUID;

/**
 * An operator decision arrived for a case that is no longer in the state the
 * operator acted on ( section 16). Either another operator already
 * decided it (approval, decline, or acknowledgement) or the row moved on. The
 * caller must NOT keep its optimistic toast: it needs the current state, so
 * this answers 409 with the observed status and the queue refreshes to show
 * the winning decision.
 */
public class DecisionConflictException extends RuntimeException {

  private final UUID transactionId;
  private final String currentStatus;
  private final boolean reviewed;

  public DecisionConflictException(UUID transactionId, String currentStatus, boolean reviewed) {
    super("Case " + transactionId + " is no longer awaiting this decision - current state: "
        + currentStatus + (reviewed ? " (reviewed)" : "") + ". Refresh the queue.");
    this.transactionId = transactionId;
    this.currentStatus = currentStatus;
    this.reviewed = reviewed;
  }

  public UUID getTransactionId() {
    return transactionId;
  }

  public String getCurrentStatus() {
    return currentStatus;
  }

  public boolean isReviewed() {
    return reviewed;
  }
}
