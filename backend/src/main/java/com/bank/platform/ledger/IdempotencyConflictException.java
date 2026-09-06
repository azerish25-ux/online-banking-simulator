package com.bank.platform.ledger;

/**
 * The same idempotency key was presented with a *different* operation intent
 * (different destination, amount, currency or memo - F06). An idempotency key
 * names one logical operation: an identical replay returns the original row,
 * but a changed payload under an already-used key is a conflict (409), never
 * a silent replay of older money and never a second posting.
 */
public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException(String message) { super(message); }
}
