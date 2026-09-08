package com.bank.platform.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The account wire shape carries authoritative principal
 * owed, interest owed, total owed, and available credit - derived server-side
 * from the loan policy (available credit is principal headroom, never
 * balance-derived). Non-loan accounts carry nulls.
 */
class AccountResponseLoanFieldsTest {

  private Account loan(BigDecimal balance, BigDecimal principal, BigDecimal creditLimit) {
    Account account = new Account(UUID.randomUUID(), "DE00000000000000000001", AccountType.LOAN);
    account.setBalance(balance);
    account.setPrincipal(principal);
    account.setCreditLimit(creditLimit);
    return account;
  }

  @Test
  void drawnLoanReportsPrincipalInterestTotalAndAvailableCreditSeparately() {
    // Drawn 700 of 1000, charged 4.8986 interest: balance -704.8986.
    Account account = loan(new BigDecimal("-704.8986"), new BigDecimal("700.0000"),
        new BigDecimal("1000.0000"));
    AccountResponse response = AccountMapper.toResponse(account);

    assertEquals("700.0000", response.principalOwed());
    assertEquals("4.8986", response.interestOwed());
    assertEquals("704.8986", response.totalOwed());
    // Available credit is the PRINCIPAL headroom (1000 - 700), NOT the balance
    // (which would claim 1000 - 704.8986 = 295.1014 and double-count interest).
    assertEquals("300.0000", response.availableCredit());
  }

  @Test
  void repaymentExtinguishesInterestBeforePrincipalInTheReportedSplit() {
    // Repaid 100 against the drawn loan above: interest 4.8986 is extinguished
    // first, the rest (95.1014) repays principal -> principal 604.8986,
    // balance -604.8986, interest now zero.
    Account account = loan(new BigDecimal("-604.8986"), new BigDecimal("604.8986"),
        new BigDecimal("1000.0000"));
    AccountResponse response = AccountMapper.toResponse(account);
    assertEquals("604.8986", response.principalOwed());
    assertEquals("0.0000", response.interestOwed());
    assertEquals("604.8986", response.totalOwed());
    assertEquals("395.1014", response.availableCredit());
  }

  @Test
  void undrawnLoanReportsZeroOwedAndFullHeadroom() {
    Account account = loan(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000.0000"));
    AccountResponse response = AccountMapper.toResponse(account);
    assertEquals("0.0000", response.principalOwed());
    assertEquals("0.0000", response.interestOwed());
    assertEquals("0.0000", response.totalOwed());
    assertEquals("1000.0000", response.availableCredit());
  }

  @Test
  void nonLoanAccountsCarryNullDebtFields() {
    Account checking = new Account(UUID.randomUUID(), "DE00000000000000000002", AccountType.CHECKING);
    checking.setBalance(new BigDecimal("500.0000"));
    AccountResponse response = AccountMapper.toResponse(checking);
    assertEquals("500.0000", response.balance());
    assertNull(response.principalOwed());
    assertNull(response.interestOwed());
    assertNull(response.totalOwed());
    assertNull(response.availableCredit());
  }
}
