package com.bank.platform.ledger;

/**
 * The ledger's currency policy, defined once. The core is single-currency
 * (USD): null/blank means USD, and anything explicit must say USD
 * case-insensitively: accepting arbitrary ISO codes would let a "EUR"
 * transfer pretend money changed currency when it simply moved USD. Two
 * money-movement services used to carry private copies of this rule; one
 * definition keeps them from drifting apart.
 */
public final class Currencies {

  private Currencies() {}

  /** Validates and normalizes a wire currency value to the ledger's one code. */
  public static String normalize(String currency) {
    if (currency == null || currency.isBlank()) {
      return "USD";
    }
    String normalized = currency.trim().toUpperCase();
    if (!"USD".equals(normalized)) {
      throw new TransferValidationException("Only USD is supported (single-currency ledger)");
    }
    return normalized;
  }
}
