package com.bank.platform.ledger;

/** What changed a loan's outstanding principal (V26). */
public enum PrincipalKind {
  /** A draw: money moved out of the loan account - principal rises. */
  DRAW,
  /** A repayment's principal component (interest was extinguished first). */
  PRINCIPAL_REPAYMENT,
  /** One-time legacy baseline at the V26 deployment boundary. */
  CUTOVER
}
