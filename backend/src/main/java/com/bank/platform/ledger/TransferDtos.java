package com.bank.platform.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class TransferDtos {

  private TransferDtos() {}

  /** Amount travels as a string so JSON never loses cents to float rounding. */
  public record TransferRequest(
      @NotBlank String toIban,
      @NotNull @Pattern(regexp = "^\\d+(\\.\\d{1,4})?$",
          message = "must be a positive amount with up to 4 decimals") String amount,
      // Single-currency ledger: null/blank means USD; anything else must say USD
      // (case-insensitive). Accepting arbitrary ISO codes would let a "EUR"
      // transfer pretend money changed currency when it simply moved USD.
      @Pattern(regexp = "(?i)\\s*USD\\s*", message = "only USD is supported (single-currency ledger)") String currency,
      @Size(max = 140) String memo,
      UUID fromAccountId) {}

  /**
   * {@code createdAt} is the request time; {@code postedAt} is when money
   * actually moved (null until a held transfer is approved - F04). The status
   * enum serializes by name; typing it here puts the legal values in the
   * contract instead of an open string.
   */
  public record TransferResponse(
      UUID id,
      @Schema(nullable = true, description = "null on deposits (no originator)") String fromIban,
      @Schema(nullable = true) String toIban,
      String amount, String currency,
      @Schema(nullable = true) String memo,
      TxStatus status, String createdAt,
      @Schema(nullable = true, description = "null while HELD or when CANCELLED - money has not moved") String postedAt,
      boolean flagged) {}

  public record MonthSummary(String month, String inflow, String outflow) {}

  /**
   * {@code createdAt} is the request time; {@code postedAt} is when money
   * actually moved (null while HELD or when CANCELLED - F04). Kind and status
   * are the domain enums, serialized by name, so the OpenAPI contract lists
   * the exact legal values.
   */
  public record TransactionResponse(
      UUID id,
      @Schema(nullable = true, description = "null on deposits/credits (no originator)") String fromIban,
      @Schema(nullable = true, description = "null on engine loan charges (no destination)") String toIban,
      String amount, String currency,
      @Schema(nullable = true) String memo,
      TxKind kind, TxStatus status, String createdAt,
      @Schema(nullable = true, description = "null while HELD or when CANCELLED - money has not moved") String postedAt,
      boolean flagged, boolean reviewed) {}

  /**
   * History feed envelope (F26). {@code items} are newest-first and
   * {@code nextCursor} is the opaque keyset position after the last item -
   * pass it back as {@code cursor} to fetch the next page, or omit it to
   * start at the newest page again. {@code null} {@code nextCursor} means the
   * final page. The cursor is bound to the account and date window that
   * produced it; changing either resets paging.
   */
  public record TransactionHistoryPage(
      List<TransactionResponse> items, long total, String nextCursor) {}

  /**
   * One recoverable operation in the authorized list (F06). Unlike the
   * history/feed shapes it carries the idempotency key itself: recovery means
   * being able to resume the EXACT same operation after a lost response or a
   * cleared browser record, which a key-less row cannot do.
   */
  public record OperationListItem(
      UUID id,
      @Schema(nullable = true) String fromIban,
      @Schema(nullable = true) String toIban,
      String amount, String currency,
      @Schema(nullable = true) String memo,
      TxKind kind, TxStatus status, String createdAt,
      @Schema(nullable = true) String postedAt,
      String idempotencyKey) {}

  /**
   * The authorized recovery list (F06): the caller's own keyed operations
   * over a bounded recent window, newest first.
   */
  public record OperationListResponse(List<OperationListItem> items) {}
}

