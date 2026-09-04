package com.bank.platform.cards;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountStatus;
import com.bank.platform.accounts.AccountType;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.ledger.TransferValidationException;
import com.bank.platform.notifications.NotificationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardService {

  public record IssuedCard(Card card, String pan, String cvv) {}

  private final UserRepository users;
  private final AccountRepository accounts;
  private final CardRepository cards;
  private final AuditLogRepository audits;
  private final NotificationService notifications;
  private final SecureRandom random = new SecureRandom();

  public CardService(
      UserRepository users,
      AccountRepository accounts,
      CardRepository cards,
      AuditLogRepository audits,
      NotificationService notifications) {
    this.users = users;
    this.accounts = accounts;
    this.cards = cards;
    this.audits = audits;
    this.notifications = notifications;
  }

  @Transactional(readOnly = true)
  public List<Card> list(String email, UUID accountId) {
    Account account = owned(email, accountId);
    return cards.findByAccountIdOrderByCreatedAtDesc(account.getId());
  }

  /**
   * Issues a Luhn-valid demo PAN. The full number and CVV are returned exactly
   * once - only hashes and the last4 are stored (tokenization-lite).
   */
  @Transactional
  public IssuedCard issue(String email, UUID accountId) {
    User user = userOf(email);
    Account account = owned(email, accountId);
    if (account.getStatus() != AccountStatus.ACTIVE) {
      throw new TransferValidationException("Account " + account.getIban() + " is not active");
    }
    if (account.getType() == AccountType.LOAN) {
      throw new TransferValidationException("Cards cannot be issued on loan accounts");
    }
    String pan = generatePan();
    String cvv = String.format("%03d", random.nextInt(1000));
    LocalDate exp = LocalDate.now().plusYears(3);
    Card card = cards.save(new Card(
        user.getId(), account.getId(), pan.substring(12), sha256(pan), sha256(cvv),
        exp.getMonthValue(), exp.getYear()));
    AuditLog issued = new AuditLog(user.getId(), "CARD_ISSUED", "Card", card.getId().toString());
    issued.setMetadata(AuditLog.metadata("last4", card.getLast4(), "account", account.getIban()));
    audits.save(issued);
    notifications.notify(user.getId(), user.getEmail(), "CARD_ISSUED",
        "Virtual card issued",
        "Card ending " + card.getLast4() + " is ready on account " + account.getIban() + ".");
    return new IssuedCard(card, pan, cvv);
  }

  @Transactional
  public Card setStatus(String email, UUID cardId, CardStatus status) {
    User user = userOf(email);
    Card card = cards.findByIdAndUserId(cardId, user.getId())
        .orElseThrow(() -> new CardNotFoundException(cardId));
    card.setStatus(status);
    cards.save(card);
    AuditLog cardStatus = new AuditLog(user.getId(),
        status == CardStatus.FROZEN ? "CARD_FROZEN" : "CARD_UNFROZEN", "Card", card.getId().toString());
    cardStatus.setMetadata(AuditLog.metadata("last4", card.getLast4()));
    audits.save(cardStatus);
    return card;
  }

  private User userOf(String email) {
    return users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  private Account owned(String email, UUID accountId) {
    User user = userOf(email);
    Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    if (!account.getUserId().equals(user.getId())) {
      throw new AccountNotFoundException(accountId);
    }
    return account;
  }

  private String generatePan() {
    StringBuilder sb = new StringBuilder("4");
    for (int i = 0; i < 14; i++) {
      sb.append(random.nextInt(10));
    }
    return sb.toString() + luhnCheckDigit(sb.toString());
  }

  static int luhnCheckDigit(String partial) {
    int sum = 0;
    boolean doubleIt = true;
    for (int i = partial.length() - 1; i >= 0; i--) {
      int digit = partial.charAt(i) - '0';
      if (doubleIt) {
        digit *= 2;
        if (digit > 9) digit -= 9;
      }
      sum += digit;
      doubleIt = !doubleIt;
    }
    return (10 - (sum % 10)) % 10;
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
