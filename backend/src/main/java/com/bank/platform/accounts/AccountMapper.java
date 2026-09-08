package com.bank.platform.accounts;

import java.math.BigDecimal;

/** Sole construction site for the account wire shape. */
public final class AccountMapper {

  private AccountMapper() {}

  public static AccountResponse toResponse(Account account) {
    return new AccountResponse(
        account.getId(),
        account.getIban(),
        account.getType(),
        account.getBalance().toPlainString(),
        account.getStatus(),
        account.getType() == AccountType.LOAN ? plain(account.getPrincipal()) : null,
        account.getType() == AccountType.LOAN ? plain(interestOwed(account)) : null,
        account.getType() == AccountType.LOAN ? plain(totalOwed(account)) : null,
        account.getType() == AccountType.LOAN ? plain(availableCredit(account)) : null);
  }

  /** A drawn loan's debt is the negative balance magnitude. */
  static BigDecimal totalOwed(Account account) {
    return account.getBalance().signum() < 0 ? account.getBalance().negate() : BigDecimal.ZERO;
  }

  /**
   * Repayments extinguish interest before principal, so any debt beyond the
   * outstanding principal is unpaid interest. Guarded at zero: a loan that is
   * fully repaid (or a legacy row anomaly) never reports negative interest.
   */
  static BigDecimal interestOwed(Account account) {
    return totalOwed(account).subtract(account.getPrincipal()).max(BigDecimal.ZERO);
  }

  /** Available credit is the PRINCIPAL headroom, not balance-based. */
  static BigDecimal availableCredit(Account account) {
    return account.getCreditLimit().subtract(account.getPrincipal()).max(BigDecimal.ZERO);
  }

  /** Ledger amounts carry 4-decimal scale; emit them verbatim (never round). */
  private static String plain(BigDecimal value) {
    return value.setScale(4, java.math.RoundingMode.UNNECESSARY).toPlainString();
  }
}
