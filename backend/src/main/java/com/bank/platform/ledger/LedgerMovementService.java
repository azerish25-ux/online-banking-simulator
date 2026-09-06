package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountStatus;
import com.bank.platform.accounts.AccountType;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The money-movement core: everything that physically moves balances between
 * two account rows. Deadlock-safe lock ordering, the active-status rule and
 * the affordability policy (LOAN floor vs. zero) live here - and only here -
 * so an instant transfer and an approved held transfer can never drift apart
 * on what they enforce. This service never opens its own transaction: callers
 * (the services that own a flow) run inside one and call in.
 */
@Service
public class LedgerMovementService {

  private final AccountRepository accounts;

  public LedgerMovementService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  /** The two locked rows after a move, so callers can record the row and emit events. */
  public record Moved(Account from, Account to) {}

  /**
   * Locks both account rows in stable ID order (deadlock-safe so concurrent
   * opposite-direction transfers cannot deadlock), validates the pair and the
   * sender's affordability, applies the debit/credit and persists both rows.
   *
   * <p>This must be the FIRST entity read of both rows in the calling
   * transaction. The lock is a JPQL {@code FOR UPDATE}, and Hibernate does
   * not refresh an entity already present in the persistence context: if a
   * caller loaded either account first, the row would be locked through a
   * stale snapshot and {@code accounts.save} would write that stale balance
   * over a newer committed one as soon as real row-lock contention makes the
   * lock wait - money silently created or destroyed (PostgreSQL exposes this;
   * H2's weaker locking hides it). Account {@code @Version} (V16) turns any
   * such future violation into a loud conflict instead of corruption, but
   * callers must still follow the first-read discipline.
   */
  public Moved move(UUID fromId, UUID toId, BigDecimal amount) {
    UUID firstId = fromId.compareTo(toId) < 0 ? fromId : toId;
    UUID secondId = firstId.equals(fromId) ? toId : fromId;
    Account first = accounts.findByIdForUpdate(firstId).orElseThrow(() -> new AccountNotFoundException(firstId));
    Account second = accounts.findByIdForUpdate(secondId).orElseThrow(() -> new AccountNotFoundException(secondId));
    Account from = first.getId().equals(fromId) ? first : second;
    Account to = first.getId().equals(toId) ? first : second;

    requireActive(from);
    requireActive(to);
    assertAffordable(from, amount);
    boolean toLoan = to.getType() == AccountType.LOAN;
    if (toLoan) {
      // A credit to a LOAN is a repayment, never a new deposit balance: it
      // is capped at the amount owed and repays interest before principal.
      assertLoanRepayment(to, amount);
    }

    from.setBalance(from.getBalance().subtract(amount));
    if (toLoan) {
      applyLoanCredit(to, amount);
    } else {
      to.setBalance(to.getBalance().add(amount));
    }
    // A draw from a LOAN is new principal - capped by assertAffordable above.
    if (from.getType() == AccountType.LOAN) {
      from.setPrincipal(from.getPrincipal().add(amount));
    }
    accounts.save(from);
    accounts.save(to);
    return new Moved(from, to);
  }

  public void requireActive(Account account) {
    if (account.getStatus() != AccountStatus.ACTIVE) {
      throw new TransferValidationException("Account " + account.getIban() + " is not active");
    }
  }

  /**
   * The authoritative check that a debit stays inside the account type's
   * floor: a LOAN may only draw against PRINCIPAL headroom (the credit limit
   * minus what is already drawn) - accrued interest never creates borrowing
   * capacity, and a loan at its limit is not silently forgiven interest, it
   * simply cannot borrow more (F16). Every other account type must stay
   * non-negative.
   */
  public void assertAffordable(Account from, BigDecimal amount) {
    if (from.getType() == AccountType.LOAN) {
      BigDecimal headroom = from.getCreditLimit().subtract(from.getPrincipal());
      if (amount.compareTo(headroom) > 0) {
        throw new InsufficientFundsException();
      }
      return;
    }
    if (from.getBalance().subtract(amount).compareTo(BigDecimal.ZERO) < 0) {
      throw new InsufficientFundsException();
    }
  }

  /**
   * A repayment (credit into a LOAN) may not exceed the amount owed, so a
   * loan balance never goes positive. Interest is extinguished before
   * principal (F16 repayment policy).
   */
  private void assertLoanRepayment(Account loan, BigDecimal amount) {
    BigDecimal owed = loan.getBalance().negate();
    if (owed.signum() < 0) {
      owed = BigDecimal.ZERO;
    }
    if (amount.compareTo(owed) > 0) {
      throw new TransferValidationException(
          "Loan repayment of " + amount.toPlainString() + " exceeds the amount owed ("
              + owed.toPlainString() + ")");
    }
  }

  /**
   * Credits the loan's balance and allocates the payment interest-first:
   * the unpaid interest (owed minus drawn principal) is extinguished first,
   * then the remainder reduces principal. The caller has already validated
   * the cap.
   */
  public void applyLoanCredit(Account loan, BigDecimal amount) {
    BigDecimal debtBefore = loan.getBalance().negate();
    BigDecimal interestBefore = debtBefore.subtract(loan.getPrincipal());
    if (interestBefore.signum() < 0) {
      interestBefore = BigDecimal.ZERO;
    }
    BigDecimal interestPaid = amount.min(interestBefore);
    loan.setBalance(loan.getBalance().add(amount));
    loan.setPrincipal(loan.getPrincipal().subtract(amount.subtract(interestPaid)));
  }

  /**
   * Simulated-rail credit into a LOAN (deposit): same repayment policy as a
   * transfer credit - capped at the amount owed, interest first.
   */
  public void creditLoan(Account loan, BigDecimal amount) {
    assertLoanRepayment(loan, amount);
    applyLoanCredit(loan, amount);
    accounts.save(loan);
  }

}
