package com.bank.platform.accounts;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.function.Function;

/**
 * German IBANs: {@code DE} + 2 mod-97 check digits + 18-digit BBAN.
 * Single home for generation and validation (ISO 13616 mod-97).
 */
public final class Iban {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Iban() {}

  public static String generate() {
    StringBuilder bban = new StringBuilder(18);
    for (int i = 0; i < 18; i++) {
      bban.append(RANDOM.nextInt(10));
    }
    int check = 98 - mod97(bban + "DE00");
    return "DE" + String.format("%02d", check) + bban;
  }

  public static boolean isValid(String iban) {
    if (iban == null) {
      return false;
    }
    String clean = iban.trim().toUpperCase();
    if (clean.length() < 15 || clean.length() > 34) {
      return false;
    }
    if (!clean.matches("[A-Z]{2}[0-9]{2}[0-9A-Z]+")) {
      return false;
    }
    return mod97(clean.substring(4) + clean.substring(0, 4)) == 1;
  }

  /**
   * Generates until {@code exists} reports a free value. Probes with SELECTs
   * (no failed writes), so the caller stays outside rollback-only territory.
   */
  public static String uniqueOrThrow(Function<String, Boolean> exists, int attempts) {
    for (int i = 0; i < attempts; i++) {
      String candidate = generate();
      if (!exists.apply(candidate)) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not generate a unique IBAN");
  }

  static int mod97(String rearranged) {
    StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
    for (char c : rearranged.toCharArray()) {
      if (c >= '0' && c <= '9') {
        numeric.append(c);
      } else {
        numeric.append(c - 'A' + 10);
      }
    }
    return new BigInteger(numeric.toString()).mod(BigInteger.valueOf(97)).intValue();
  }
}
