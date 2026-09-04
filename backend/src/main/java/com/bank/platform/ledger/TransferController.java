package com.bank.platform.ledger;

import com.bank.platform.accounts.AccountService;
import com.bank.platform.ledger.TransferDtos.MonthSummary;
import com.bank.platform.ledger.TransferDtos.TransactionResponse;
import com.bank.platform.ledger.TransferDtos.TransferRequest;
import com.bank.platform.ledger.TransferDtos.TransferResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
  public Page<TransactionResponse> history(
      Authentication authentication,
      @RequestParam UUID accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    // Ownership check first: throws 404 for foreign or missing accounts.
    accounts.accountDetail(authentication.getName(), accountId);
    Instant fromInstant = from == null
        ? Instant.EPOCH
        : from.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant toInstant = to == null
        ? Instant.now().plusSeconds(3600)
        : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    int safeSize = Math.min(Math.max(size, 1), 100);
    int safePage = Math.max(page, 0);
    List<Transaction> rows = transactions.historyPage(
        accountId, fromInstant, toInstant, safeSize, safePage * safeSize);
    long total = transactions.historyCount(accountId, fromInstant, toInstant);
    Map<UUID, String> ibans = statements.ibanMap(rows);
    List<TransactionResponse> mapped = rows.stream().map(tx -> TransactionMapper.toResponse(tx, ibans)).toList();
    return new PageImpl<>(mapped, PageRequest.of(safePage, safeSize), total);
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
    StatementService.Statement statement = statements.customerStatement(authentication.getName(), id, from, to);
    var account = statement.account();
    // Only settled rows appear in a statement - HELD/CANCELLED intents never
    // moved money, so they must not read as movements (matches the PDF).
    List<Transaction> rows = statement.rows().stream()
        .filter(tx -> tx.getStatus() == TxStatus.POSTED)
        .toList();
    Map<UUID, String> ibans = statements.ibanMap(rows);

    StringBuilder csv = new StringBuilder("id,created_at,from_iban,to_iban,amount,currency,memo,status\n");
    for (Transaction tx : rows) {
      csv.append(tx.getId()).append(',')
          .append(tx.getCreatedAt()).append(',')
          .append(cell(tx.getFromAccountId() == null ? "" : ibans.getOrDefault(tx.getFromAccountId(), ""))).append(',')
          .append(cell(tx.getToAccountId() == null ? "" : ibans.getOrDefault(tx.getToAccountId(), ""))).append(',')
          .append(tx.getAmount().toPlainString()).append(',')
          .append(tx.getCurrency()).append(',')
          .append(cell(tx.getMemo() == null ? "" : tx.getMemo())).append(',')
          .append(tx.getStatus().name()).append('\n');
    }

    String filename = "statement-" + account.getIban() + "-" + LocalDate.now() + ".csv";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .contentType(MediaType.parseMediaType("text/csv"))
        .body(csv.toString());
  }

  @GetMapping("/accounts/{id}/statement.pdf")
  public ResponseEntity<byte[]> statementPdf(
      Authentication authentication,
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    StatementService.Statement statement = statements.customerStatement(authentication.getName(), id, from, to);
    byte[] pdf = statements.renderPdf(statement);
    String filename = "statement-" + statement.account().getIban() + ".pdf";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }

  /**
   * CSV-escapes one cell. Cells are always quoted; user-controlled values that
   * start with a spreadsheet formula character (= + - @ or a tab) are prefixed
   * with a single quote so opening the export in Excel/Sheets cannot execute a
   * formula smuggled through a memo.
   */
  private String cell(String value) {
    String safe = value == null ? "" : value;
    if (!safe.isEmpty()) {
      char first = safe.charAt(0);
      if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
        safe = "'" + safe;
      }
    }
    return '"' + safe.replace("\"", "\"\"") + '"';
  }
}
