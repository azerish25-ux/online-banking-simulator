package com.bank.platform.common;

/**
 * One definition of the product name. The simulator deliberately carries no
 * invented bank brand - just a description of what it is - so the UI, PDF
 * headers, TOTP labels and JWT claims cannot drift apart from each other.
 */
public final class Brand {

  public static final String NAME = "Online Banking Simulator";
  public static final String JWT_ISSUER = "online-banking-simulator";
  // Audience for tokens that authenticate protected requests (access tokens).
  public static final String JWT_AUDIENCE = "simulator-web";
  // Audience for the short-lived MFA login challenge. Distinct from the access
  // audience so a challenge can never be mistaken for (or replayed as) an
  // authenticated session even if a purpose claim were stripped.
  public static final String JWT_MFA_AUDIENCE = "simulator-mfa-challenge";
  public static final String PDF_STATEMENT_HEADER = NAME + " - Account statement";

  private Brand() {}
}
