package com.bank.platform.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Human-facing money formatting for notifications, PDFs and other copy: the
 * ledger keeps four decimals, but people read two. Audit metadata and machine
 * exports (CSV) intentionally keep the raw scale; this class only formats for
 * eyes. DecimalFormat is not thread-safe, so each call builds its own.
 *
 * Display rounding is HALF_UP, i.e. half away from zero for BOTH signs: an
 * exact-cent tie like -1.005 reads "-1.01", never silently toward zero. (The
 * settlement path rounds HALF_EVEN at the ledger's 4-decimal scale: see
 * MoneyService: which is a separate, stored-value concern.)
 */
public final class Money {

  private Money() {}

  /**
   * "$1,234.56" (negatives "-$600.00"): for notifications, PDF headers and
   * other user-facing copy. The sign goes BEFORE the symbol: DecimalFormat
   * would otherwise emit "$-600.00", which reads like a broken currency.
   */
  public static String usd(BigDecimal value) {
    // Explicit US symbols: a server under a de_DE locale would otherwise emit
    // "$1.234,56" (comma decimal, dot grouping) into notification copy.
    DecimalFormat format = new DecimalFormat(
        "#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    format.setRoundingMode(RoundingMode.HALF_UP);
    BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
    String sign = scaled.signum() < 0 ? "-" : "";
    // Round first, then sign: a value that rounds to zero (e.g. -0.004) must
    // print "$0.00", not a misleading "-$0.00".
    return sign + "$" + format.format(scaled.abs());
  }

  /** "1234.56" with no grouping or symbol: for aligned PDF table cells. */
  public static String plain(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
