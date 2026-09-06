package com.bank.platform.cards;

import com.bank.platform.common.ApiExceptionHandler;
import com.bank.platform.common.ApiProblem;
import java.util.List;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CardController {

  private final CardService service;

  public CardController(CardService service) {
    this.service = service;
  }

  public record CardResponse(UUID id, String last4, int expMonth, int expYear, CardStatus status) {
    static CardResponse from(Card card) {
      return new CardResponse(
          card.getId(), card.getLast4(), card.getExpMonth(), card.getExpYear(), card.getStatus());
    }
  }

  public record IssuedCardResponse(
      UUID id, String pan, String cvv, int expMonth, int expYear, CardStatus status) {}

  @GetMapping("/accounts/{id}/cards")
  public List<CardResponse> list(Authentication authentication, @PathVariable UUID id) {
    return service.list(authentication.getName(), id).stream().map(CardResponse::from).toList();
  }

  @PostMapping("/accounts/{id}/cards")
  @ResponseStatus(HttpStatus.CREATED)
  public IssuedCardResponse issue(Authentication authentication, @PathVariable UUID id) {
    CardService.IssuedCard issued = service.issue(authentication.getName(), id);
    Card card = issued.card();
    return new IssuedCardResponse(
        card.getId(), issued.pan(), issued.cvv(), card.getExpMonth(), card.getExpYear(), card.getStatus());
  }

  @PostMapping("/cards/{id}/freeze")
  public CardResponse freeze(Authentication authentication, @PathVariable UUID id) {
    return CardResponse.from(service.setStatus(authentication.getName(), id, CardStatus.FROZEN));
  }

  @PostMapping("/cards/{id}/unfreeze")
  public CardResponse unfreeze(Authentication authentication, @PathVariable UUID id) {
    return CardResponse.from(service.setStatus(authentication.getName(), id, CardStatus.ACTIVE));
  }

  @ExceptionHandler(CardNotFoundException.class)
  public ResponseEntity<ApiProblem> notFound(CardNotFoundException ex) {
    return ApiExceptionHandler.response(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }
}
