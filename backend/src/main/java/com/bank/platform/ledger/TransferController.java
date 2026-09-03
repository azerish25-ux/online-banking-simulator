package com.bank.platform.ledger;

import com.bank.platform.accounts.AccountController;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.ledger.TransferDtos.TransactionResponse;
import com.bank.platform.ledger.TransferDtos.TransferRequest;
import com.bank.platform.ledger.TransferDtos.TransferResponse;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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

  public TransferController(MoneyService money, TransactionRepository transactions, AccountRepository accounts) {
    this.money = money;
    this.transactions = transactions;
    this.accounts = accounts;
  }

  @PostMapping("/transfers")
  @ResponseStatus(HttpStatus.CREATED)
  public TransferResponse transfer(
      Authentication authentication,
      @Valid @RequestBody TransferRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // Default source: the customer's first account (explicit fromAccountId arrives in Part 5).
    UUID fromId = money.myAccounts(authentication.getName()).stream()
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
      @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
    // Ownership check first: throws 403/404 for foreign or missing accounts.
    money.accountDetail(authentication.getName(), accountId);
    Page<Transaction> page = transactions.findByAccountId(accountId, pageable);
    java.util.Set<UUID> ids = page.getContent().stream()
        .flatMap(tx -> java.util.stream.Stream.of(tx.getFromAccountId(), tx.getToAccountId()))
        .filter(id -> id != null)
        .collect(Collectors.toSet());
    Map<UUID, String> ibans = accounts.findAllById(ids).stream()
        .collect(Collectors.toMap(a -> a.getId(), a -> a.getIban()));
    return page.map(tx -> toDto(tx, ibans));
  }

  private TransferResponse toDto(Transaction tx) {
    Map<UUID, String> ibans = accounts.findAllById(
            java.util.stream.Stream.of(tx.getFromAccountId(), tx.getToAccountId())
                .filter(id -> id != null)
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
        tx.getCreatedAt().toString());
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
        tx.getCreatedAt().toString());
  }
}
