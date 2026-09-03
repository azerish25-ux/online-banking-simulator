package com.bank.platform.accounts;

import com.bank.platform.ledger.MoneyService;
import com.bank.platform.ledger.TransferDtos.AccountResponse;
import com.bank.platform.ledger.TransferDtos.DepositRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

  private final MoneyService money;

  public AccountController(MoneyService money) {
    this.money = money;
  }

  @GetMapping
  public List<AccountResponse> mine(Authentication authentication) {
    return money.myAccounts(authentication.getName()).stream().map(AccountController::toDto).toList();
  }

  @GetMapping("/{id}")
  public AccountResponse detail(Authentication authentication, @PathVariable UUID id) {
    return toDto(money.accountDetail(authentication.getName(), id));
  }

  @PostMapping("/{id}/deposit")
  public AccountResponse deposit(
      Authentication authentication, @PathVariable UUID id, @Valid @RequestBody DepositRequest request) {
    return toDto(money.deposit(authentication.getName(), id, new BigDecimal(request.amount())));
  }

  static AccountResponse toDto(Account account) {
    return new AccountResponse(
        account.getId(),
        account.getIban(),
        account.getType(),
        account.getBalance().toPlainString(),
        account.getStatus());
  }
}
