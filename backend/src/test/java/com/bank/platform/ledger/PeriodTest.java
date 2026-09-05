package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The inclusive-day window is the single owner of the half-open bound rule. */
class PeriodTest {

  private static final ZoneOffset UTC = ZoneOffset.UTC;

  @Test
  void inclusiveToDayEndsAtTheStartOfTheNextDay() {
    Period window = new Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    assertEquals(Instant.parse("2026-09-01T00:00:00Z"), window.start());
    // 30 September is IN the window; 00:00 on 1 October is the first instant out.
    assertEquals(Instant.parse("2026-10-01T00:00:00Z"), window.endExclusive());
  }

  @Test
  void singleDayWindowOwnsBothBounds() {
    Period day = Period.day(LocalDate.of(2026, 1, 1));
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), day.start());
    assertEquals(Instant.parse("2026-01-02T00:00:00Z"), day.endExclusive());
  }

  @Test
  void monthAndYearEndsDoNotShiftTheExclusiveBound() {
    Period yearEnd = Period.day(LocalDate.of(2025, 12, 31));
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), yearEnd.endExclusive());

    // Leap February: the window opens on 28 February and closes AFTER 29
    // February - the 29th is in it, and the exclusive end lands on 1 March.
    Period leapEnd = new Period(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 2, 29));
    assertEquals(Instant.parse("2028-02-28T00:00:00Z"), leapEnd.start());
    assertEquals(Instant.parse("2028-03-01T00:00:00Z"), leapEnd.endExclusive());
  }

  @Test
  void defaultWindowRunsFromThirtyDaysAgoThroughToday() {
    Period defaults = Period.lastThirtyDays();
    LocalDate today = LocalDate.now(UTC);
    assertEquals(today, defaults.to());
    assertEquals(today.minusDays(30), defaults.from());
    assertEquals(today.plusDays(1).atStartOfDay(UTC).toInstant(), defaults.endExclusive());
  }

  @Test
  void sinceAndUntilOpenOneSideAndLeaveTheBoundNull() {
    LocalDate day = LocalDate.of(2026, 9, 5);
    Period since = Period.since(day);
    assertEquals(Instant.parse("2026-09-05T00:00:00Z"), since.start());
    assertNull(since.endExclusive(), "an open upper side has no exclusive end");

    Period until = Period.until(day);
    assertNull(until.start(), "an open lower side has no start");
    assertEquals(Instant.parse("2026-09-06T00:00:00Z"), until.endExclusive());
  }

  @Test
  void invertedRangeIsRejectedRatherThanSilentlyEmpty() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> new Period(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)));
    assertTrue(ex.getMessage().contains("inverted"), ex.getMessage());
    // A same-day window and the open factories are not inverted.
    assertEquals(Period.day(LocalDate.of(2026, 9, 1)),
        new Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)));
  }
}
