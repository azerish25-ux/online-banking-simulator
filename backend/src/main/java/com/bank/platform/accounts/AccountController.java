package com.bank.platform.accounts;

import com.bank.platform.ledger.MoneyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

  private final AccountService accounts;
  private final MoneyService money;

  public AccountController(AccountService accounts, MoneyService money) {
    this.accounts = accounts;
    this.money = money;
  }

  @GetMapping
  public List<AccountResponse> mine(Authentication authentication) {
    return accounts.myAccounts(authentication.getName()).stream().map(AccountMapper::toResponse).toList();
  }

  public record OpenAccountRequest(@NotBlank String type) {}

  /** Amount travels as a string so JSON never loses cents to float rounding. */
  public record DepositRequest(
      @NotNull @Pattern(regexp = "^\\d+(\\.\\d{1,4})?$",
          message = "must be a positive amount with up to 4 decimals") String amount) {}

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public AccountResponse open(Authentication authentication, @Valid @RequestBody OpenAccountRequest request) {
    return AccountMapper.toResponse(accounts.openAccount(authentication.getName(), request.type()));
  }

  @GetMapping("/{id}")
  public AccountResponse detail(Authentication authentication, @PathVariable UUID id) {
    return AccountMapper.toResponse(accounts.accountDetail(authentication.getName(), id));
  }

  /**
   * Every user-submitted funding must carry an idempotency key (F06); the
   * service enforces it (the header is read here and forwarded). An identical
   * replay returns the account's current state; reusing the key for a
   * different amount is a 409 conflict.
   */
  @PostMapping("/{id}/deposit")
  public AccountResponse deposit(
      Authentication authentication, @PathVariable UUID id, @Valid @RequestBody DepositRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    return AccountMapper.toResponse(
        money.deposit(authentication.getName(), id, new BigDecimal(request.amount()), idempotencyKey));
  }

}
