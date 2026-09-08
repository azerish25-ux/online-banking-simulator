package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountService;
import com.bank.platform.accounts.AccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The statement snapshot assembler and orchestrator. It owns the ONE place a
 * statement is built - account, rows, opening/closing and IBAN labels read in
 * a single REPEATABLE_READ snapshot and copied into immutable value records -
 * and then hands that {@link Statement} to pure renderers that do no financial
 * queries of their own:
 *
 * <ul>
 *   <li>{@link StatementCsvRenderer} - formula-safe CSV serialization;</li>
 *   <li>{@link StatementPdfRenderer} - the A4/PDF layout engine (measured
 *       wrapping, ICU-shaping + bidi RTL text, page footer and true page
 *       count).</li>
 * </ul>
 *
 * No domain class in this service does byte-level rendering.
 */
@Service
public class StatementService {

  private final AccountService accounts;
  private final AccountRepository accountRepository;
  private final TransactionRepository transactions;
  private final Clock clock;
  private final TransactionTemplate snapshotTx;
  private final long maxRows;
  private final StatementCsvRenderer csv = new StatementCsvRenderer();
  private final StatementPdfRenderer pdf = new StatementPdfRenderer();

  public StatementService(
      AccountService accounts,
      AccountRepository accountRepository,
      TransactionRepository transactions,
      PlatformTransactionManager transactionManager,
      Clock clock,
      @Value("${app.statement.max-rows:5000}") long maxRows) {
    this.accounts = accounts;
    this.accountRepository = accountRepository;
    this.transactions = transactions;
    this.clock = clock;
    this.maxRows = maxRows;
    DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
    definition.setReadOnly(true);
    definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshotTx = new TransactionTemplate(transactionManager, definition);
  }

  /**
   * One immutable, fully materialized statement. The whole document is
   * built from a SINGLE coherent read: one account read, one row query, one
   * opening-cut aggregate and one IBAN resolution, all inside one
   * REPEATABLE_READ transaction, so every figure and row on the page comes
   * from the same database snapshot. {@code opening + sum(printed rows) =
   * closing} holds BY CONSTRUCTION - the closing is derived from the printed
   * rows - and the opening is the projection balance minus the in-window and
   * post-window net, never today's balance pasted onto a past period.
   *
   * <p>{@code asOf} names the database snapshot the figures came from and
   * {@code version} the statement schema, so re-issuing the same request
   * later produces a visibly NEW document (a later asOf), never a silent
   * mutation of an earlier one.
   *
   * <p>The statement carries ONLY immutable VALUE data: account and
   * row shapes are plain records copied at snapshot time, never live JPA
   * entities - a row that posts after the snapshot, or a memo changed later,
   * can never mutate what a rendered document already holds. Renderers
   * (PDF/CSV) accept this record and perform no financial queries of their
   * own, so the bytes can never combine figures from different snapshots.
   */
  public record Statement(
      StatementAccount account,
      List<StatementRow> rows,
      Period period,
      Map<UUID, String> ibans,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      Instant asOf,
      long version) {

    /** The statement schema version these documents are rendered from. */
    public static final long CURRENT_VERSION = 3;

    public Statement {
      rows = List.copyOf(rows);
      ibans = Map.copyOf(ibans);
    }
  }

  /** Immutable account facts a statement needs - never the mutable entity. */
  public record StatementAccount(UUID id, String iban, String type, String status) {
    static StatementAccount of(Account a) {
      return new StatementAccount(
          a.getId(), a.getIban(), a.getType().name(), a.getStatus().name());
    }
  }

  /**
   * Immutable statement row: the settled values of ONE posted instruction at
   * snapshot time. Legs are raw account ids (the {@code ibans} map resolves
   * display labels); direction relative to the statement account is derived
   * by the renderer from the legs, so a row can never claim a direction its
   * legs do not support.
   */
  public record StatementRow(
      UUID id,
      Instant postedAt,
      UUID fromAccountId,
      UUID toAccountId,
      BigDecimal amount,
      String currency,
      String memo,
      TxStatus status) {
    static StatementRow of(Transaction tx) {
      return new StatementRow(
          tx.getId(), tx.getPostedAt(), tx.getFromAccountId(), tx.getToAccountId(),
          tx.getAmount(), tx.getCurrency(), tx.getMemo(), tx.getStatus());
    }
  }

  /** A ready-to-stream CSV export: the server-chosen filename and its body. */
  public record CsvStatement(String filename, String content) {}

  public CsvStatement customerCsv(String email, UUID accountId, LocalDate from, LocalDate to) {
    // The CSV snapshot runs in an EXPLICIT repeatable-read transaction, not
    // through a self-invoked @Transactional method: Spring's proxy advice does
    // not apply when a bean calls its own annotated method, so leaning on the
    // annotation here would silently downgrade the CSV to the caller's
    // isolation. The template is the boundary; the read inside it is
    // one coherent snapshot and the CSV renderer serializes it without more
    // queries.
    Statement statement = snapshotTx.execute(status -> {
      Account account = accounts.accountDetail(email, accountId);
      return build(account, from, to);
    });
    return csv.render(statement);
  }

  /**
   * The customer statement: one REPEATABLE_READ transaction builds account,
   * rows, opening/closing and IBAN labels from one database snapshot.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Statement customerStatement(String email, UUID accountId, LocalDate from, LocalDate to) {
    Account account = accounts.accountDetail(email, accountId);
    return build(account, from, to);
  }

  /** Operator-side twin of {@link #customerStatement} (admin authorization is the caller's job). */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Statement adminStatement(UUID accountId, LocalDate from, LocalDate to) {
    Account account = accountRepository.findById(accountId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
    return build(account, from, to);
  }

  /** The requested window, or the default (today and the 30 days before) when open. */
  private static Period window(LocalDate from, LocalDate to) {
    Period defaults = Period.lastThirtyDays();
    return new Period(from != null ? from : defaults.from(), to != null ? to : defaults.to());
  }

  /** Counterpart-IBAN lookup for rows of one account window (same transaction as the build). */
  public Map<UUID, String> ibanMap(List<Transaction> rows) {
    Set<UUID> ids = rows.stream()
        .flatMap(tx -> Stream.of(tx.getFromAccountId(), tx.getToAccountId()))
        .filter(value -> value != null)
        .collect(Collectors.toSet());
    return accountRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(Account::getId, Account::getIban));
  }

  /**
   * The one place a statement is assembled. All reads happen in the caller's
   * single REPEATABLE_READ transaction; the arithmetic below then derives
   * closing from the printed rows so the identity opening + rows = closing
   * cannot drift, while opening stays a true period-start balance (current
   * projection minus every settled movement at/after the window start - the
   * same +to/-from convention {@link TransactionRepository#sumSettledMovementAfter}
   * owns). A statement for a past period never prints today's balance.
   */
  private Statement build(Account account, LocalDate from, LocalDate to) {
    Period period = window(from, to);
    Instant asOf = clock.instant();
    // Every value the document will ever show is COPIED here, at snapshot
    // time: the statement holds no reference to the mutable Account/Transaction
    // entities, so nothing the database does later can change the document.
    StatementAccount statementAccount = StatementAccount.of(account);
    List<Transaction> settled = rows(account.getId(), period);
    List<StatementRow> rows = settled.stream().map(StatementRow::of).toList();
    Map<UUID, String> ibans = ibanMap(settled);
    BigDecimal netFromStart = netSettledFrom(account.getId(), period.start());
    BigDecimal opening = account.getBalance().subtract(netFromStart);
    BigDecimal closing = opening;
    for (StatementRow row : rows) {
      // Only POSTED rows reach this list (rows() filters), so every row here
      // moved money: credit the account when it received, debit otherwise.
      boolean credit = statementAccount.id().equals(row.toAccountId());
      closing = credit ? closing.add(row.amount()) : closing.subtract(row.amount());
    }
    return new Statement(statementAccount, rows, period, ibans, opening, closing, asOf,
        Statement.CURRENT_VERSION);
  }

  private List<Transaction> rows(UUID accountId, Period period) {
    // Guard before loading: a statement for a huge window must not pull every
    // matching row into memory just to render (or to stream out as CSV). The
    // count and the rows share the same settled-in-window definition.
    long matching = transactions.postedCount(accountId, period);
    if (matching > maxRows) {
      throw new TransferValidationException(
          "Statement covers " + matching + " transactions (max " + maxRows
              + "); narrow the date range");
    }
    return transactions.statementRows(accountId, period);
  }

  private BigDecimal netSettledFrom(UUID accountId, Instant from) {
    return transactions.sumSettledMovementAfter(accountId, TxStatus.POSTED, from)
        .orElse(BigDecimal.ZERO);
  }

  /**
   * Renders the statement to PDF bytes - see {@link StatementPdfRenderer} for
   * the layout engine (measured wrapping, ICU-shaping + bidi RTL text, page
   * footer and true page count). The renderer is pure: it reads only the
   * immutable {@link Statement}, so rendering the same statement twice
   * produces identical bytes even after money moves in the database.
   */
  public byte[] renderPdf(Statement statement) {
    return pdf.render(statement);
  }
}
