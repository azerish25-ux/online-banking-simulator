package com.bank.platform.ledger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque keyset cursor for the transaction-history feed.
 *
 * <p>The page ordering key is {@code seq}, the database-assigned monotonic
 * insert identity (V14). It is immutable, total (no two rows share one), and
 * precise: unlike {@code created_at}, which rows persisted in one flush
 * share and which can round-trip differently through timestamp columns. A
 * page ends with the last row's {@code seq} encoded here, and the next page
 * continues with {@code seq &lt; last} instead of an OFFSET that re-scans and
 * can duplicate or skip rows when anything is inserted between two reads.
 *
 * <p>Deliberately NOT a "snapshot": new rows keep arriving, so the feed is a
 * live history: immutable rows page without duplication, and a client that
 * wants to see fresh rows restarts at the first page. A cursor is bound to
 * the account and date window that produced it; changing either resets
 * paging to the newest page (clients do this, and the contract documents it).
 */
public final class HistoryCursor {

  private HistoryCursor() {}

  /** Encodes a {@code seq} into a compact URL-safe token. */
  public static String encode(long seq) {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(Long.toString(seq).getBytes(StandardCharsets.UTF_8));
  }

  /** Decodes a token back to its {@code seq}. Malformed tokens are a 400. */
  public static long decode(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("cursor must not be blank");
    }
    String plain;
    try {
      plain = new String(
          Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("malformed history cursor", ex);
    }
    try {
      return Long.parseLong(plain);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("malformed history cursor", ex);
    }
  }
}
