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

    from.setBalance(from.getBalance().subtract(amount));
    to.setBalance(to.getBalance().add(amount));
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
   * floor: a LOAN account may draw down to the negation of its credit limit,
   * every other account type must stay non-negative.
   */
  public void assertAffordable(Account from, BigDecimal amount) {
    BigDecimal floor = from.getType() == AccountType.LOAN ? from.getCreditLimit().negate() : BigDecimal.ZERO;
    if (from.getBalance().subtract(amount).compareTo(floor) < 0) {
      throw new InsufficientFundsException();
    }
  }
}
