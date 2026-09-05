package com.bank.platform.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

  public record TransferResponse(
      UUID id, String fromIban, String toIban, String amount, String currency,
      String memo, String status, String createdAt, boolean flagged) {}

  public record MonthSummary(String month, String inflow, String outflow) {}

  public record TransactionResponse(
      UUID id, String fromIban, String toIban, String amount, String currency,
      String memo, String kind, String status, String createdAt,
      boolean flagged, boolean reviewed) {}
}

