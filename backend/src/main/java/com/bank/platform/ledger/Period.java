package com.bank.platform.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * An inclusive calendar-day window over the ledger. Both {@code from} and
 * {@code to} name whole days that belong to the window; a {@code null} side
 * means the window is open on that side (no lower, or no upper, bound). Every
 * consumer: the statement row query, the statement balance cut, the PDF
 * "Period ... to ..." label, the CSV rows, the activity history filter: reads the
 * SAME half-open instant range {@code [start(), endExclusive())}, where an
 * open side flows through to SQL as "no constraint" rather than a made-up
 * instant. The inclusive-`to` rule therefore lives here once: a caller can no
 * longer combine an inclusive day with a half-open bound and drift a day (the
 * bug class that made a past window's statement print today's balance as its
 * closing).
 *
 * <p>A window whose two named days run backwards is rejected in the
 * constructor: the same mistake used to surface as a silent empty result on
 * every read (200 with nothing in it), which is worse than an error because
 * the caller cannot tell "no rows match" from "your range is impossible".
 */
public record Period(LocalDate from, LocalDate to) {

  private static final ZoneOffset UTC = ZoneOffset.UTC;

  public Period {
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException(
          "Date range is inverted: from (" + from + ") is after to (" + to + ")");
    }
  }

  /** Every day from {@code from} onward (open on the upper side). */
  public static Period since(LocalDate from) {
    return new Period(from, null);
  }

  /** Every day up to and including {@code to} (open on the lower side). */
  public static Period until(LocalDate to) {
    return new Period(null, to);
  }

  /** One inclusive day: for a filter that names a single date. */
  public static Period day(LocalDate date) {
    return new Period(date, date);
  }

  /** The default statement window: today and the 30 calendar days before it. */
  public static Period lastThirtyDays() {
    LocalDate today = LocalDate.now(UTC);
    return new Period(today.minusDays(30), today);
  }

  /** Inclusive lower bound: the first instant of {@code from}, or {@code null} when open. */
  public Instant start() {
    return from == null ? null : from.atStartOfDay(UTC).toInstant();
  }

  /** Exclusive upper bound: the first instant of the day AFTER {@code to}, or {@code null} when open. */
  public Instant endExclusive() {
    return to == null ? null : to.plusDays(1).atStartOfDay(UTC).toInstant();
  }
}
