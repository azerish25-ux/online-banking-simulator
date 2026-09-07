package com.bank.platform.ledger;

import com.bank.platform.accounts.AccountService;
import com.bank.platform.ledger.TransferDtos.MonthSummary;
import com.bank.platform.ledger.TransferDtos.TransactionHistoryPage;
import com.bank.platform.ledger.TransferDtos.TransactionResponse;
import com.bank.platform.ledger.TransferDtos.TransferRequest;
import com.bank.platform.ledger.TransferDtos.TransferResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TransferController {

  private final AccountService accounts;
  private final MoneyService money;
  private final TransactionRepository transactions;
  private final StatementService statements;
  private final TransactionHistoryDao historyDao;

  public TransferController(AccountService accounts, MoneyService money, TransactionRepository transactions,
      StatementService statements, TransactionHistoryDao historyDao) {
    this.accounts = accounts;
    this.money = money;
    this.transactions = transactions;
    this.statements = statements;
    this.historyDao = historyDao;
  }

  @PostMapping("/transfers")
  @ResponseStatus(HttpStatus.CREATED)
  public TransferResponse transfer(
      Authentication authentication,
      @Valid @RequestBody TransferRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    UUID fromId = request.fromAccountId() != null
        ? request.fromAccountId()
        : accounts.myAccounts(authentication.getName()).stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No source account found"))
            .getId();
    Transaction tx = money.transfer(
        authentication.getName(),
        fromId,
        request.toIban(),
        new BigDecimal(request.amount()),
        request.currency(),
        request.memo(),
        idempotencyKey);
    return TransactionMapper.toTransferResponse(tx, statements.ibanMap(List.of(tx)));
  }

  @GetMapping("/transactions")
  public TransactionHistoryPage history(
      Authentication authentication,
      @RequestParam UUID accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String minAmount,
      @RequestParam(required = false) String maxAmount,
      @RequestParam(required = false) List<String> kind,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String q) {
    // Ownership check first: throws 404 for foreign or missing accounts.
    accounts.accountDetail(authentication.getName(), accountId);
    int safeSize = Math.min(Math.max(size, 1), 100);
    // A dated filter is an inclusive-day window whose half-open bounds Period
    // owns; a null side means the side is open (the queries are null-tolerant,
    // so no sentinel bounds exist). An inverted from/to pair is rejected by
    // Period and surfaces as a 400 before any query runs. The cursor is the
    // opaque position AFTER the previously returned page (F26): an absent or
    // null cursor is the newest page, and a malformed one is a 400 - keyset
    // paging has no "absurd depth" to clamp because it never re-scans an
    // offset.
    Period window = new Period(from, to);
    // section 14 server-backed filters: amount range, kind(s), state(s) and a
    // reference/counterparty search are VALIDATED here (400 before any query)
    // and then become SQL predicates over the whole account history - never a
    // client-side filter of the loaded page. "Searching" a 10-row page is
    // deliberately impossible: the DAO's predicates run on the database.
    BigDecimal min = decimalFilter(minAmount, "minAmount");
    BigDecimal max = decimalFilter(maxAmount, "maxAmount");
    if (min != null && max != null && min.compareTo(max) > 0) {
      throw new IllegalArgumentException(
          "Amount range is inverted: minAmount (" + min.toPlainString()
              + ") is above maxAmount (" + max.toPlainString() + ")");
    }
    List<String> kinds = enumFilter(kind, TxKind.class, "kind");
    List<String> statuses = enumFilter(status, TxStatus.class, "status");
    String term = q == null || q.isBlank() ? null : q.trim();
    if (term != null && term.length() < 2) {
      // A one-character LIKE over every memo and counter-party IBAN is not a
      // search - it is a scan. Bound expensive searches (section 14).
      throw new IllegalArgumentException(
          "Search term must be at least 2 characters");
    }
    Long cursorSeq = cursor == null || cursor.isBlank() ? null : HistoryCursor.decode(cursor);
    boolean filtered = min != null || max != null || !kinds.isEmpty() || !statuses.isEmpty()
        || term != null;
    List<Transaction> rows;
    long total;
    if (filtered) {
      TransactionHistoryDao.HistoryFilter filter = new TransactionHistoryDao.HistoryFilter(
          accountId, window.start(), window.endExclusive(), min, max, kinds, statuses, term);
      rows = historyDao.page(filter, cursorSeq, safeSize + 1);
      total = historyDao.count(filter);
    } else {
      // No extra filters: the exact original keyset query keeps its proven
      // path (identical semantics and indexes).
      rows = transactions.historyPage(accountId, window, cursorSeq, safeSize + 1);
      total = transactions.historyCount(accountId, window);
    }
    boolean hasMore = rows.size() > safeSize;
    List<Transaction> page = hasMore ? rows.subList(0, safeSize) : rows;
    Map<UUID, String> ibans = statements.ibanMap(page);
    List<TransactionResponse> mapped =
        page.stream().map(tx -> TransactionMapper.toResponse(tx, ibans)).toList();
    Transaction last = page.isEmpty() ? null : page.get(page.size() - 1);
    String nextCursor = null;
    if (hasMore && last != null) {
      // The ordering key's seq is DB-assigned and never written back to the
      // mapped entity (see TransactionRepository.rawSeqOf), so read it from
      // the table - a keyset cursor built from a null seq would corrupt the
      // next page's boundary.
      Long seq = transactions.rawSeqOf(last.getId())
          .orElseThrow(() -> new IllegalStateException("transaction row lacks its seq key"));
      nextCursor = HistoryCursor.encode(seq);
    }
    return new TransactionHistoryPage(mapped, total, nextCursor);
  }

  /** Parses an exact ledger amount filter (at most 4 fraction digits). */
  private static BigDecimal decimalFilter(String raw, String name) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    if (!value.matches("-?\\d+(\\.\\d{1,4})?")) {
      throw new IllegalArgumentException(
          name + " must be a ledger decimal with at most 4 fraction digits");
    }
    return new BigDecimal(value);
  }

  /** Validates enum filters by their STRING names (a bad name is a 400). */
  private static <E extends Enum<E>> List<String> enumFilter(
      List<String> raw, Class<E> type, String name) {
    if (raw == null) {
      return List.of();
    }
    List<String> names = new java.util.ArrayList<>();
    for (String value : raw) {
      if (value == null || value.isBlank()) {
        continue;
      }
      String candidate = value.trim().toUpperCase();
      try {
        Enum.valueOf(type, candidate);
      } catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException(
            "Unknown " + name + " filter: " + value + " (supported: "
                + java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name)
                    .collect(java.util.stream.Collectors.joining(", ")) + ")");
      }
      names.add(candidate);
    }
    return names;
  }

  /**
   * Operation-status lookup by idempotency key (F06). Ownership is
   * originator-scoped: only the user whose account carries the key may see
   * the operation - a foreign or unknown key is indistinguishable (404), so
   * probing never discloses another user's row. A client that lost the
   * response resolves its key here before offering another submit.
   *
   * <p>Because keys are namespaced to their originating account, a key-only
   * lookup can match several DIFFERENT operations of one caller (one per
   * owned account). Passing the originating {@code accountId} makes the
   * lookup unambiguous; when several matches exist and no account is given
   * the server answers 409 with the candidates rather than picking an
   * arbitrary one.
   */
  @GetMapping("/operations")
  public TransactionResponse operation(
      Authentication authentication, @RequestParam String key,
      @RequestParam(required = false) UUID accountId) {
    Transaction tx = money.operationStatus(authentication.getName(), key, accountId)
        .orElseThrow(() -> new TransactionNotFoundException(
            "No operation found for this idempotency key"));
    return TransactionMapper.toResponse(tx, statements.ibanMap(List.of(tx)));
  }

  /**
   * Authorized recovery list (F06 namespace fix): the caller's own recent
   * keyed transfers and deposits, newest first, bounded - including
   * completed-but-unacknowledged postings. After a lost response, a reload
   * or a re-login the owner can rediscover what a key actually did without
   * relying on browser storage.
   */
  @GetMapping("/operations/recent")
  public TransferDtos.OperationListResponse recentOperations(
      Authentication authentication, @RequestParam(defaultValue = "25") int limit) {
    List<Transaction> rows = money.recentOperations(authentication.getName(), limit);
    Map<UUID, String> ibans = statements.ibanMap(rows);
    return new TransferDtos.OperationListResponse(
        rows.stream().map(tx -> TransactionMapper.toOperationListItem(tx, ibans)).toList());
  }

  /**
   * One of the caller's own transactions by id (F11 durable receipt). The
   * receipt URL is bookmarkable: it carries only the operation id, and the
   * lookup is originator-scoped server-side (a foreign or unknown id is the
   * same 404, so nothing is disclosed). Re-fetching after a HELD→POSTED
   * transition returns the current authoritative status and posting time.
   */
  @GetMapping("/transfers/{id}")
  public TransactionResponse transferDetail(
      Authentication authentication, @PathVariable UUID id) {
    Transaction tx = money.transferDetail(authentication.getName(), id)
        .orElseThrow(() -> new TransactionNotFoundException(
            "No transfer found for this id"));
    return TransactionMapper.toResponse(tx, statements.ibanMap(List.of(tx)));
  }

  @GetMapping("/accounts/{id}/summary")
  public List<MonthSummary> summary(
      Authentication authentication,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "6") int months) {
    return money.summary(authentication.getName(), id, months);
  }

  @GetMapping("/accounts/{id}/statement.csv")
  public ResponseEntity<String> statement(
      Authentication authentication,
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    // Row selection, IBAN resolution, the POSTED filter and the formula-safe
    // escaping all live in StatementService next to the PDF renderer, so the
    // two exports cannot drift on what a statement means.
    StatementService.CsvStatement csv =
        statements.customerCsv(authentication.getName(), id, from, to);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(csv.filename()).build().toString())
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(csv.content());
  }

  @GetMapping("/accounts/{id}/statement.pdf")
  public ResponseEntity<byte[]> statementPdf(
      Authentication authentication,
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    StatementService.Statement statement = statements.customerStatement(authentication.getName(), id, from, to);
    byte[] pdf = statements.renderPdf(statement);
    String filename = "statement-" + statement.account().iban() + ".pdf";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }

}
