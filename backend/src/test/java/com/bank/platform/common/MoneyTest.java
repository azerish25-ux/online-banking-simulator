package com.bank.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Money copy is user-facing: grouping and decimal separators must stay US even
 * when the server runs under another default locale - otherwise notifications
 * and PDF headers silently switch to "1.234,56" style output.
 */
class MoneyTest {

  @Test
  void usdIsLocaleIndependent() {
    Locale original = Locale.getDefault();
    try {
      // A German default locale formats 1234.56 as "1.234,56".
      Locale.setDefault(Locale.GERMANY);
      assertEquals("$1,234.56", Money.usd(new BigDecimal("1234.56")),
          "usd() must pin US separators regardless of the JVM default locale");
      assertEquals("$0.10", Money.usd(new BigDecimal("0.1")));
      assertEquals("$1,234,567.89", Money.usd(new BigDecimal("1234567.89")));
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void plainKeepsTwoDecimalScale() {
    assertEquals("1234.56", Money.plain(new BigDecimal("1234.564")));
    assertEquals("-0.50", Money.plain(new BigDecimal("-0.5")));
  }

  /**
   * Display ties round half away from zero on BOTH signs. BigDecimal HALF_UP
   * means exactly that: -1.005 must read "-1.01" (toward the larger |value|),
   * never "-1.00" - the request that spawned this test assumed the opposite
   * and would have "fixed" it into the wrong-looking result.
   */
  @Test
  void negativeDisplayTiesRoundAwayFromZero() {
    assertEquals("-1.01", Money.plain(new BigDecimal("-1.005")));
    assertEquals("1.01", Money.plain(new BigDecimal("1.005")));
    assertEquals("-$1.01", Money.usd(new BigDecimal("-1.005")));
  }

  /**
   * Negative amounts are debt, and debt copy reads "-$600.00", not
   * "$-600.00" - this is what loan statements (Opening/Closing on a drawn
   * loan) would otherwise print. Runs under a foreign locale to prove the
   * sign placement never regresses with the symbols.
   */
  @Test
  void usdPutsTheSignBeforeTheSymbolForNegatives() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      assertEquals("-$600.00", Money.usd(new BigDecimal("-600")));
      assertEquals("-$1,234.56", Money.usd(new BigDecimal("-1234.564")));
      // Rounds to zero first: no spurious negative zero in print copy.
      assertEquals("$0.00", Money.usd(new BigDecimal("-0.004")));
      assertEquals("-$0.01", Money.usd(new BigDecimal("-0.005")));
    } finally {
      Locale.setDefault(original);
    }
  }
}
