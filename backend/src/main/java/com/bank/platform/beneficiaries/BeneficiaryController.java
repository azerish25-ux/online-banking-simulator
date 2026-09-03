package com.bank.platform.beneficiaries;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/beneficiaries")
public class BeneficiaryController {

  private final BeneficiaryService service;

  public BeneficiaryController(BeneficiaryService service) {
    this.service = service;
  }

  public record CreateRequest(
      @NotBlank @Size(max = 80) String nickname,
      @NotBlank @Size(max = 34) String iban) {}

  public record BeneficiaryResponse(UUID id, String nickname, String iban, Instant createdAt) {
    static BeneficiaryResponse from(Beneficiary b) {
      return new BeneficiaryResponse(b.getId(), b.getNickname(), b.getIban(), b.getCreatedAt());
    }
  }

  @GetMapping
  public List<BeneficiaryResponse> mine(Authentication authentication) {
    return service.mine(authentication.getName()).stream().map(BeneficiaryResponse::from).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BeneficiaryResponse add(Authentication authentication, @Valid @RequestBody CreateRequest request) {
    return BeneficiaryResponse.from(service.add(authentication.getName(), request.nickname(), request.iban()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(Authentication authentication, @PathVariable UUID id) {
    service.remove(authentication.getName(), id);
  }
}
