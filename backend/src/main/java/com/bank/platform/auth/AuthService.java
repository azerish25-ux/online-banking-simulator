package com.bank.platform.auth;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountType;
import com.bank.platform.accounts.Iban;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final AuditLogRepository audits;
  private final PasswordEncoder passwords;
  private final TotpService totp;
  private final TotpThrottle totpThrottle;
  private final RefreshTokenRepository refreshTokens;

  public AuthService(
      UserRepository users,
      AccountRepository accounts,
      AuditLogRepository audits,
      PasswordEncoder passwords,
      TotpService totp,
      TotpThrottle totpThrottle,
      RefreshTokenRepository refreshTokens) {
    this.users = users;
    this.accounts = accounts;
    this.audits = audits;
    this.passwords = passwords;
    this.totp = totp;
    this.totpThrottle = totpThrottle;
    this.refreshTokens = refreshTokens;
  }

  @Transactional
  @org.springframework.cache.annotation.CacheEvict(value = "public-stats", allEntries = true)
  public User register(String email, String rawPassword, String fullName) {
    String normalized = email.trim().toLowerCase();
    if (users.existsByEmail(normalized)) {
      throw new EmailTakenException(normalized);
    }
    User user = users.save(new User(normalized, passwords.encode(rawPassword), fullName.trim()));
    accounts.save(new Account(user.getId(), Iban.uniqueOrThrow(accounts::existsByIban, 5), AccountType.CHECKING));
    audits.save(AuditLog.of(user.getId(), "USER_REGISTERED", "User", user.getId().toString(),
        "email", user.getEmail()));
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

  /**
   * Rotates the TOTP secret. Audited; if 2FA was already active, changing the
   * secret also revokes every refresh token so the account re-authenticates
   * with the new factor rather than sailing on old sessions.
   */
  @Transactional
  public String startTotpSetup(String email) {
    User user = userOf(email);
    String secret = totp.newSecret();
    user.setTotpSecret(secret);
    users.save(user);
    audits.save(metadataAudit(user, "TOTP_SETUP"));
    if (user.isTotpEnabled()) {
      refreshTokens.revokeAllByUserId(user.getId());
    }
    return secret;
  }

  @Transactional
  public User enableTotp(String email, String code) {
    User user = userOf(email);
    // A session holder must not be able to brute-force the six-digit code at
    // network speed to enable 2FA with their own (or a victim's) session.
    totpThrottle.verifyAvailable(email);
    if (user.getTotpSecret() == null || !totp.verify(user.getTotpSecret(), code)) {
      totpThrottle.recordFailure(email);
      throw new BadCredentialsException("Invalid code");
    }
    totpThrottle.recordSuccess(email);
    user.setTotpEnabled(true);
    users.save(user);
    audits.save(metadataAudit(user, "TOTP_ENABLED"));
    refreshTokens.revokeAllByUserId(user.getId());
    return user;
  }

  @Transactional
  public User disableTotp(String email, String code) {
    User user = userOf(email);
    totpThrottle.verifyAvailable(email);
    if (!user.isTotpEnabled() || user.getTotpSecret() == null || !totp.verify(user.getTotpSecret(), code)) {
      totpThrottle.recordFailure(email);
      throw new BadCredentialsException("Invalid code");
    }
    totpThrottle.recordSuccess(email);
    user.setTotpEnabled(false);
    user.setTotpSecret(null);
    users.save(user);
    audits.save(metadataAudit(user, "TOTP_DISABLED"));
    refreshTokens.revokeAllByUserId(user.getId());
    return user;
  }

  private AuditLog metadataAudit(User user, String action) {
    return AuditLog.of(user.getId(), action, "User", user.getId().toString(),
        "email", user.getEmail());
  }

  private User userOf(String email) {
    return users.findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }
}
