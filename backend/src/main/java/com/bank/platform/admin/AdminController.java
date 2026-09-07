package com.bank.platform.admin;

import com.bank.platform.accounts.AccountMapper;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountResponse;
import com.bank.platform.accounts.AccountStatus;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.AuthDtos.UserResponse;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.ledger.InterestService;
import com.bank.platform.ledger.Period;
import com.bank.platform.ledger.ReconciliationService;
import com.bank.platform.ledger.ReversalService;
import com.bank.platform.ledger.StatementService;
import com.bank.platform.ledger.Transaction;
import com.bank.platform.ledger.TransactionKindReviewRepository;
import com.bank.platform.ledger.TransactionMapper;
import com.bank.platform.ledger.TransactionRepository;
import com.bank.platform.ledger.TransactionSpecs;
import com.bank.platform.ledger.TransferDtos.TransactionResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
  private final InterestService interestService;
  private final ReportService reportService;
  private final StatementService statementService;
  private final ReconciliationService reconciliationService;
  private final ReversalService reversalService;
  private final TransactionKindReviewRepository kindReviews;
  private final com.bank.platform.notifications.EmailOutboxService emailOutbox;

  public AdminController(
      UserRepository users,
      AccountRepository accounts,
      TransactionRepository transactions,
      AuditLogRepository audits,
      AdminService adminService,
      InterestService interestService,
      ReportService reportService,
      StatementService statementService,
      ReconciliationService reconciliationService,
      ReversalService reversalService,
      TransactionKindReviewRepository kindReviews,
      com.bank.platform.notifications.EmailOutboxService emailOutbox) {
    this.users = users;
    this.accounts = accounts;
    this.transactions = transactions;
    this.audits = audits;
    this.adminService = adminService;
    this.interestService = interestService;
    this.reportService = reportService;
    this.statementService = statementService;
    this.reconciliationService = reconciliationService;
    this.reversalService = reversalService;
    this.kindReviews = kindReviews;
    this.emailOutbox = emailOutbox;
  }

  /**
   * Dead-lettered (or any-status) external mail, for the operator's retry
   * path (F25). Only PENDING rows are in flight; FAILED rows have exhausted
   * their delivery budget and need a decision. Recipient addresses are the
   * operator's business - this is the internal ops tool, not a public feed.
   */
  @GetMapping("/email-outbox")
  public org.springframework.data.domain.Page<EmailOutboxRow> emailOutbox(
      @RequestParam(required = false) com.bank.platform.notifications.EmailOutbox.Status status,
      @PageableDefault(size = 20) org.springframework.data.domain.Pageable pageable) {
    return emailOutbox.list(status, capped(pageable)).map(EmailOutboxRow::from);
  }

  /** Requeue one dead letter for another bounded delivery attempt (F25). */
  @PostMapping("/email-outbox/{id}/retry")
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.NO_CONTENT)
  public void requeueEmail(@PathVariable UUID id) {
    if (!emailOutbox.requeue(id)) {
      throw new com.bank.platform.ledger.TransactionNotFoundException(
          "No dead-lettered email row with that id");
    }
  }

  /** Operator-facing view of one outbox row - errors stay redacted. */
  public record EmailOutboxRow(
      UUID id, String email, String subject, String status, int attempts,
      String lastError, String createdAt, String nextAttemptAt) {
    static EmailOutboxRow from(com.bank.platform.notifications.EmailOutbox row) {
      return new EmailOutboxRow(
          row.getId(), row.getEmail(), row.getSubject(), row.getStatus().name(),
          row.getAttempts(), row.getLastError(),
          row.getCreatedAt().toString(), row.getNextAttemptAt().toString());
    }
  }

  /**
   * Independent reconciliation (F15): derived-from-journal account checks,
   * per-currency balancing, duplicate operation entries and unexplained
   * movements. Operator-visible so a drift between the journal and the
   * balance projections is surfaced instead of silently absorbed.
   */
  @GetMapping("/reconciliation")
  public ReconciliationService.ReconciliationReport reconciliation() {
    return reconciliationService.reconcile();
  }

  /**
   * Audit rows carry JSON metadata (amounts, counterparty IBANs, emails,
   * account numbers) that made the log auditable in the first place - the
   * viewer surfaces it instead of hiding the trail's substance.
   */
  public record AuditResponse(
      Long id, UUID actorId, String action, String entity, String entityId,
      Map<String, String> metadata, String createdAt) {
    static AuditResponse from(AuditLog log) {
      return new AuditResponse(
          log.getId(), log.getActorId(), log.getAction(), log.getEntity(), log.getEntityId(),
          AuditLog.metadataMap(log.getMetadata()),
          log.getCreatedAt().toString());
    }
  }

  @GetMapping("/users")
  public Page<UserResponse> users(
      @RequestParam(defaultValue = "") String q,
      @PageableDefault(size = 20) Pageable pageable) {
    Pageable capped = capped(pageable);
    if (q.isBlank()) {
      return users.findAll(capped).map(UserResponse::from);
    }
    return users.findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCase(q, q, capped)
        .map(UserResponse::from);
  }

  @GetMapping("/users/{id}/accounts")
  public List<AccountResponse> userAccounts(@PathVariable UUID id) {
    return accounts.findByUserIdOrderByCreatedAtAsc(id).stream()
        .map(AccountMapper::toResponse).toList();
  }

  @GetMapping("/transactions")
  public Page<TransactionResponse> transactions(
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) Boolean flagged,
      @RequestParam(required = false) Boolean reviewed,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
    // A dated bound is its inclusive day; Period owns the half-open instants
    // (a null side stays an unfiltered read, and an inverted pair is rejected
    // here rather than returning a silently empty page).
    Period bounds = new Period(from, to);
    Instant fromInstant = bounds.start();
    Instant toInstant = bounds.endExclusive();
    Page<Transaction> page = transactions.findAll(
        TransactionSpecs.filters(accountId, flagged, reviewed, fromInstant, toInstant),
        withInsertionTiebreak(capped(pageable)));
    Map<UUID, String> ibans = statementService.ibanMap(page.getContent());
    // The operator surface shows the full reversal picture (reason on a
    // REVERSAL row, and which POSTED rows already have one) so the console
    // never offers a second reversal of the same instruction.
    Map<UUID, UUID> reversalIndex = transactions.reversalIndexBy(page.getContent());
    return page.map(tx -> TransactionMapper.toAdminResponse(tx, ibans, reversalIndex));
  }

  @GetMapping("/audit-logs")
  public Page<AuditResponse> auditLogs(
      @RequestParam(required = false) String action,
      @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
    Pageable capped = capped(pageable);
    if (action != null && !action.isBlank()) {
      return audits.findByAction(action.trim().toUpperCase(), capped).map(AuditResponse::from);
    }
    return audits.findAll(capped).map(AuditResponse::from);
  }

  @PostMapping("/interest/run")
  public Map<String, Integer> runInterest() {
    return interestService.accrueMonthly();
  }

  /**
   * The F21 kind-review quarantine: every legacy transaction whose kind was
   * corrected from structural/audit evidence, or labelled UNCERTAIN because no
   * evidence proved it, is listed here with its original classification and
   * the reason. Operators see exactly what was decided - nothing was silently
   * re-tagged or rebalanced to make reports fit.
   */
  @GetMapping("/kind-review")
  public List<KindReviewResponse> kindReview() {
    return kindReviews.findAllByOrderByCreatedAtDesc().stream()
        .map(row -> new KindReviewResponse(
            row.getTransactionId().toString(), row.getPriorKind(), row.getReason(),
            row.getMemoSnippet(), row.getCreatedAt().toString()))
        .toList();
  }

  /** One archived classification decision (F21). */
  public record KindReviewResponse(
      String transactionId, String priorKind, String reason,
      @io.swagger.v3.oas.annotations.media.Schema(nullable = true) String memoSnippet,
      String reviewedAt) {}

  /**
   * Operator decision bodies ( section 16): a bounded REQUIRED reason
   * plus the case state the operator saw. A body that omits them is accepted
   * only for programmatic callers (a bounded default reason is recorded); the
   * console always sends the exact state it displayed, so a stale decision
   * answers 409 instead of overwriting another operator's outcome.
   */
  public record DecisionRequest(
      @jakarta.validation.constraints.Size(max = 400) String reason,
      String expectedStatus,
      Boolean expectedReviewed) {}

  @PostMapping("/transactions/{id}/review")
  public TransactionResponse review(Authentication authentication, @PathVariable UUID id,
      @RequestBody(required = false) @jakarta.validation.Valid DecisionRequest request) {
    Transaction tx = adminService.reviewTransaction(authentication.getName(), id,
        request == null ? null : request.reason(),
        request == null ? null : request.expectedStatus(),
        request == null ? null : request.expectedReviewed());
    return operatorResponse(tx);
  }

  @PostMapping("/transactions/{id}/decline")
  public TransactionResponse decline(Authentication authentication, @PathVariable UUID id,
      @RequestBody(required = false) @jakarta.validation.Valid DecisionRequest request) {
    Transaction tx = adminService.declineTransaction(authentication.getName(), id,
        request == null ? null : request.reason(),
        request == null ? null : request.expectedStatus(),
        request == null ? null : request.expectedReviewed());
    return operatorResponse(tx);
  }

  /**
   * Authorized reversal of a POSTED transaction (V29). A new REVERSAL row
   * moves the money back along the original legs and is journaled as a linked
   * correction; the original row is untouched. The operator's reason is
   * mandatory and preserved on the row and in the audit trail.
   */
  @PostMapping("/transactions/{id}/reverse")
  public TransactionResponse reverse(Authentication authentication, @PathVariable UUID id,
      @RequestBody(required = false) ReversalRequest request) {
    Transaction tx = reversalService.reverse(authentication.getName(), id,
        request == null ? null : request.reason());
    return operatorResponse(tx);
  }

  /** The operator's mandatory reversal reason (validated in ReversalService). */
  public record ReversalRequest(String reason) {}

  /** One settled row for the operator surface, with its live reversal state. */
  private TransactionResponse operatorResponse(Transaction tx) {
    Map<UUID, String> ibans = statementService.ibanMap(List.of(tx));
    Map<UUID, UUID> reversalIndex = transactions.reversalIndexBy(List.of(tx));
    return TransactionMapper.toAdminResponse(tx, ibans, reversalIndex);
  }

  @GetMapping("/reports/daily-totals")
  public List<ReportService.DayTotal> dailyTotals(@RequestParam(defaultValue = "30") int days) {
    return reportService.dailyTotals(days);
  }

  @GetMapping("/accounts/{id}/statement.pdf")
  public ResponseEntity<byte[]> adminStatementPdf(
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    StatementService.Statement statement = statementService.adminStatement(id, from, to);
    return pdf(statement);
  }

  @PostMapping("/accounts/{id}/freeze")
  public AccountResponse freeze(Authentication authentication, @PathVariable UUID id) {
    return AccountMapper.toResponse(
        adminService.setStatus(authentication.getName(), id, AccountStatus.FROZEN));
  }

  @PostMapping("/accounts/{id}/unfreeze")
  public AccountResponse unfreeze(Authentication authentication, @PathVariable UUID id) {
    return AccountMapper.toResponse(
        adminService.setStatus(authentication.getName(), id, AccountStatus.ACTIVE));
  }

  /**
   * No admin listing may stream unbounded rows because a caller asked for
   * size=9999999 - cap the page size (and never page backwards off the start).
   */
  private static Pageable capped(Pageable pageable) {
    int size = Math.min(Math.max(pageable.getPageSize(), 1), 100);
    int page = Math.max(pageable.getPageNumber(), 0);
    return PageRequest.of(page, size, pageable.getSort());
  }

  /**
   * Transactions tied on created_at (rows persisted in one flush share a
   * timestamp) resolve by insertion sequence, newest first. The UUID id is
   * random, so without this the queue order for tied rows is arbitrary and a
   * page boundary can duplicate or skip rows.
   */
  private static Pageable withInsertionTiebreak(Pageable pageable) {
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
        pageable.getSort().and(Sort.by(Sort.Direction.DESC, "seq")));
  }

  private ResponseEntity<byte[]> pdf(StatementService.Statement statement) {
    byte[] pdf = statementService.renderPdf(statement);
    String filename = "statement-" + statement.account().iban() + ".pdf";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(filename).build().toString())
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf);
  }

}
