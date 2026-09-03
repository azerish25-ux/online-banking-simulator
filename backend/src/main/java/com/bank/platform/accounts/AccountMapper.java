package com.bank.platform.accounts;

import com.bank.platform.ledger.TransferDtos.AccountResponse;

/** Sole construction site for the account wire shape. */
public final class AccountMapper {

  private AccountMapper() {}

  public static AccountResponse toResponse(Account account) {
    return new AccountResponse(
        account.getId(),
        account.getIban(),
        account.getType().name(),
        account.getBalance().toPlainString(),
        account.getStatus().name());
  }
}
