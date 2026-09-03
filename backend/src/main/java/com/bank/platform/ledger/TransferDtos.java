package com.bank.platform.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class TransferDtos {

  private TransferDtos() {}

  /** Amount travels as a string so JSON never loses cents to float rounding. */
  public record TransferRequest(
      @NotBlank String toIban,
      @Pattern(regexp = "^\\d+(\\.\\d{1,4})?$", message = "must be a positive amount with up to 4 decimals") String amount,
      @Size(min = 3, max = 3) String currency,
      @Size(max = 140) String memo,
      UUID fromAccountId) {}

  public record DepositRequest(
      @Pattern(regexp = "^\\d+(\\.\\d{1,4})?$", message = "must be a positive amount with up to 4 decimals") String amount) {}

  public record TransferResponse(
      UUID id, String fromIban, String toIban, String amount, String currency,
      String memo, String status, String createdAt) {}

  public record AccountResponse(
      UUID id, String iban, String type, String balance, String status) {}

  public record MonthSummary(String month, String inflow, String outflow) {}

  public record TransactionResponse(
      UUID id, String fromIban, String toIban, String amount, String currency,
      String memo, String status, String createdAt) {}
}

