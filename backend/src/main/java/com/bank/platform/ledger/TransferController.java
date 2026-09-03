package com.bank.platform.ledger;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.ledger.TransferDtos.MonthSummary;
import com.bank.platform.ledger.TransferDtos.TransactionResponse;
import com.bank.platform.ledger.TransferDtos.TransferRequest;
import com.bank.platform.ledger.TransferDtos.TransferResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

  private final MoneyService money;
  private final TransactionRepository transactions;
  private final AccountRepository accounts;
  private final StatementService statements;

  public TransferController(MoneyService money, TransactionRepository transactions, AccountRepository accounts,
      StatementService statements) {
    this.money = money;
    this.transactions = transactions;
    this.accounts = accounts;
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
        : money.myAccounts(authentication.getName()).stream()
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
    return toDto(tx);
  }

  @GetMapping("/transactions")
  public Page<TransactionResponse> history(
      Authentication authentication,
      @RequestParam UUID accountId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    // Ownership check first: throws 403/404 for foreign or missing accounts.
    money.accountDetail(authentication.getName(), accountId);
    java.time.Instant fromInstant = from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    java.time.Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    Page<Transaction> page = transactions.findAll(
        TransactionSpecs.filters(accountId, null, null, fromInstant, toInstant), pageable);
    return page.map(tx -> toDto(tx, ibanMap(page.getContent())));
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
    List<Transaction> rows = statement.rows();
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
          .append(tx.getStatus()).append('\n');
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

  private Map<UUID, String> ibanMap(List<Transaction> rows) {
    java.util.Set<UUID> ids = rows.stream()
        .flatMap(tx -> java.util.stream.Stream.of(tx.getFromAccountId(), tx.getToAccountId()))
        .filter(value -> value != null)
        .collect(Collectors.toSet());
    return accounts.findAllById(ids).stream()
        .collect(Collectors.toMap(a -> a.getId(), a -> a.getIban()));
  }

  private String cell(String value) {
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  private TransferResponse toDto(Transaction tx) {
    Map<UUID, String> ibans = accounts.findAllById(
            java.util.stream.Stream.of(tx.getFromAccountId(), tx.getToAccountId())
                .filter(value -> value != null)
                .toList())
        .stream()
        .collect(Collectors.toMap(a -> a.getId(), a -> a.getIban()));
    return new TransferResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.get(tx.getFromAccountId()),
        tx.getToAccountId() == null ? null : ibans.get(tx.getToAccountId()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getStatus(),
        tx.getCreatedAt().toString(), tx.isFlagged());
  }

  private TransactionResponse toDto(Transaction tx, Map<UUID, String> ibans) {
    return new TransactionResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.getOrDefault(tx.getFromAccountId(), tx.getFromAccountId().toString()),
        tx.getToAccountId() == null ? null : ibans.getOrDefault(tx.getToAccountId(), tx.getToAccountId().toString()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getStatus(),
        tx.getCreatedAt().toString(), tx.isFlagged(), tx.isReviewed());
  }
}
