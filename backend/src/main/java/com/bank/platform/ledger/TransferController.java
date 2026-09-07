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

  public TransferController(AccountService accounts, MoneyService money, TransactionRepository transactions,
      StatementService statements) {
    this.accounts = accounts;
    this.money = money;
    this.transactions = transactions;
    this.statements = statements;
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
      @RequestParam(required = false) String cursor) {
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
    Long cursorSeq = cursor == null || cursor.isBlank() ? null : HistoryCursor.decode(cursor);
    // Fetch one extra row to learn whether another page exists.
    List<Transaction> rows =
        transactions.historyPage(accountId, window, cursorSeq, safeSize + 1);
    long total = transactions.historyCount(accountId, window);
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
