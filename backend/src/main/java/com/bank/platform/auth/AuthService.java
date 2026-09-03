package com.bank.platform.auth;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import java.security.SecureRandom;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final AuditLogRepository audits;
  private final PasswordEncoder passwords;
  private final SecureRandom random = new SecureRandom();

  public AuthService(
      UserRepository users,
      AccountRepository accounts,
      AuditLogRepository audits,
      PasswordEncoder passwords) {
    this.users = users;
    this.accounts = accounts;
    this.audits = audits;
    this.passwords = passwords;
  }

  @Transactional
  public User register(String email, String rawPassword, String fullName) {
    String normalized = email.trim().toLowerCase();
    if (users.existsByEmail(normalized)) {
      throw new EmailTakenException(normalized);
    }
    User user = users.save(new User(normalized, passwords.encode(rawPassword), fullName.trim()));
    accounts.save(new Account(user.getId(), generateIban(), "CHECKING"));
    audits.save(new AuditLog(user.getId(), "USER_REGISTERED", "User", user.getId().toString()));
    return user;
  }

  @Transactional(readOnly = true)
  public User login(String email, String rawPassword) {
    String normalized = email.trim().toLowerCase();
    User user = users.findByEmail(normalized)
        .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
    if (!passwords.matches(rawPassword, user.getPasswordHash())) {
      throw new BadCredentialsException("Invalid email or password");
    }
    return user;
  }

  private String generateIban() {
    StringBuilder sb = new StringBuilder("DE");
    for (int i = 0; i < 20; i++) {
      sb.append(random.nextInt(10));
    }
    return sb.toString();
  }
}
