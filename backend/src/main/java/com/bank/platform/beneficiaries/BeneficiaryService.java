package com.bank.platform.beneficiaries;

import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BeneficiaryService {

  private final BeneficiaryRepository beneficiaries;
  private final UserRepository users;

  public BeneficiaryService(BeneficiaryRepository beneficiaries, UserRepository users) {
    this.beneficiaries = beneficiaries;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public List<Beneficiary> mine(String email) {
    return beneficiaries.findByUserIdOrderByNickname(userOf(email).getId());
  }

  @Transactional
  public Beneficiary add(String email, String nickname, String iban) {
    User user = userOf(email);
    String cleanIban = iban.trim().toUpperCase();
    String cleanName = nickname.trim();
    if (cleanName.isEmpty()) {
      throw new IllegalArgumentException("Nickname is required");
    }
    if (!cleanIban.matches("[A-Z]{2}[0-9A-Z]{11,32}")) {
      throw new IllegalArgumentException("IBAN looks invalid");
    }
    if (beneficiaries.existsByUserIdAndIban(user.getId(), cleanIban)) {
      throw new BeneficiaryExistsException(cleanIban);
    }
    try {
      return beneficiaries.saveAndFlush(new Beneficiary(user.getId(), cleanName, cleanIban));
    } catch (DataIntegrityViolationException race) {
      throw new BeneficiaryExistsException(cleanIban);
    }
  }

  @Transactional
  public void remove(String email, UUID id) {
    User user = userOf(email);
    Beneficiary found = beneficiaries.findByIdAndUserId(id, user.getId())
        .orElseThrow(() -> new BeneficiaryNotFoundException(id));
    beneficiaries.delete(found);
  }

  private User userOf(String email) {
    return users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }
}
