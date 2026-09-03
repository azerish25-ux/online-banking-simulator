package com.bank.platform.admin;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.AuthDtos.UserResponse;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.ledger.TransactionRepository;
import com.bank.platform.ledger.TransferDtos.AccountResponse;
import com.bank.platform.ledger.StatementService;
import com.bank.platform.ledger.TransferDtos.TransactionResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final AuditLogRepository audits;
  private final AdminService adminService;
  private final com.bank.platform.ledger.InterestService interestService;
  private final com.bank.platform.admin.ReportService reportService;
  private final com.bank.platform.ledger.StatementService statementService;

  public AdminController(
      UserRepository users,
      AccountRepository accounts,
      TransactionRepository transactions,
      AuditLogRepository audits,
      AdminService adminService,
      com.bank.platform.ledger.InterestService interestService,
      com.bank.platform.admin.ReportService reportService,
      com.bank.platform.ledger.StatementService statementService) {
    this.users = users;
    this.accounts = accounts;
    this.transactions = transactions;
    this.audits = audits;
    this.adminService = adminService;
    this.interestService = interestService;
    this.reportService = reportService;
    this.statementService = statementService;
  }

  public record AuditResponse(
      Long id, UUID actorId, String action, String entity, String entityId, String createdAt) {
    static AuditResponse from(AuditLog log) {
      return new AuditResponse(
          log.getId(), log.getActorId(), log.getAction(), log.getEntity(), log.getEntityId(),
          log.getCreatedAt().toString());
    }
  }

  @GetMapping("/users")
  public Page<UserResponse> users(
      @RequestParam(defaultValue = "") String q,
      @PageableDefault(size = 20) Pageable pageable) {
    if (q.isBlank()) {
      return users.findAll(pageable).map(UserResponse::from);
    }
    return users.findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCase(q, q, pageable)
        .map(UserResponse::from);
  }

  @GetMapping("/users/{id}/accounts")
  public java.util.List<AccountResponse> userAccounts(@PathVariable UUID id) {
    return accounts.findByUserIdOrderByCreatedAtAsc(id).stream().map(AdminController::toDto).toList();
  }

  @GetMapping("/transactions")
  public Page<TransactionResponse> transactions(
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) Boolean flagged,
      @RequestParam(required = false) Boolean reviewed,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    java.time.Instant fromInstant = from == null ? null : from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    java.time.Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    Page<com.bank.platform.ledger.Transaction> page = transactions.findAll(
        com.bank.platform.ledger.TransactionSpecs.filters(accountId, flagged, reviewed, fromInstant, toInstant),
        pageable);
    java.util.Set<UUID> ids = page.getContent().stream()
        .flatMap(tx -> java.util.stream.Stream.of(tx.getFromAccountId(), tx.getToAccountId()))
        .filter(value -> value != null)
        .collect(Collectors.toSet());
    Map<UUID, String> ibans = accounts.findAllById(ids).stream()
        .collect(Collectors.toMap(Account::getId, Account::getIban));
    return page.map(tx -> new TransactionResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.getOrDefault(tx.getFromAccountId(), tx.getFromAccountId().toString()),
        tx.getToAccountId() == null ? null : ibans.getOrDefault(tx.getToAccountId(), tx.getToAccountId().toString()),
        tx.getAmount().toPlainString(),
        tx.getCurrency(),
        tx.getMemo(),
        tx.getStatus(),
        tx.getCreatedAt().toString(), tx.isFlagged(), tx.isReviewed()));
  }

  @GetMapping("/audit-logs")
  public Page<AuditResponse> auditLogs(
      @RequestParam(required = false) String action,
      @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
    if (action != null && !action.isBlank()) {
      return audits.findByAction(action.trim().toUpperCase(), pageable).map(AuditResponse::from);
    }
    return audits.findAll(pageable).map(AuditResponse::from);
  }

  @PostMapping("/interest/run")
  public java.util.Map<String, Integer> runInterest() {
    return interestService.accrueMonthly();
  }

  @PostMapping("/transactions/{id}/review")
  public TransactionResponse review(Authentication authentication, @PathVariable UUID id) {
    com.bank.platform.ledger.Transaction tx = adminService.reviewTransaction(authentication.getName(), id);
    Map<UUID, String> ibans = statementService.ibanMap(List.of(tx));
    return new TransactionResponse(
        tx.getId(),
        tx.getFromAccountId() == null ? null : ibans.getOrDefault(tx.getFromAccountId(), tx.getFromAccountId().toString()),
        tx.getToAccountId() == null ? null : ibans.getOrDefault(tx.getToAccountId(), tx.getToAccountId().toString()),
        tx.getAmount().toPlainString(), tx.getCurrency(), tx.getMemo(), tx.getStatus(),
        tx.getCreatedAt().toString(), tx.isFlagged(), tx.isReviewed());
  }

  @GetMapping("/reports/daily-totals")
  public java.util.List<ReportService.DayTotal> dailyTotals(@RequestParam(defaultValue = "30") int days) {
    return reportService.dailyTotals(days);
  }

  @GetMapping("/accounts/{id}/statement.pdf")
  public org.springframework.http.ResponseEntity<byte[]> adminStatementPdf(
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    StatementService.Statement statement = statementService.adminStatement(id, from, to);
    return pdf(statement);
  }

  @PostMapping("/accounts/{id}/freeze")
  public AccountResponse freeze(Authentication authentication, @PathVariable UUID id) {
    return toDto(adminService.setStatus(authentication.getName(), id, "FROZEN"));
  }

  @PostMapping("/accounts/{id}/unfreeze")
  public AccountResponse unfreeze(Authentication authentication, @PathVariable UUID id) {
    return toDto(adminService.setStatus(authentication.getName(), id, "ACTIVE"));
  }

  private org.springframework.http.ResponseEntity<byte[]> pdf(StatementService.Statement statement) {
    byte[] pdf = statementService.renderPdf(statement);
    String filename = "statement-" + statement.account().getIban() + ".pdf";
    return org.springframework.http.ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
            org.springframework.http.ContentDisposition.attachment().filename(filename).build().toString())
        .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
        .body(pdf);
  }

  private static AccountResponse toDto(Account account) {
    return new AccountResponse(
        account.getId(), account.getIban(), account.getType(),
        account.getBalance().toPlainString(), account.getStatus());
  }
}
