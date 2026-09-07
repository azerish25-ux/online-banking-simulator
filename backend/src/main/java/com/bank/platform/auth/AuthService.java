package com.bank.platform.auth;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountType;
import com.bank.platform.accounts.Iban;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.common.LedgerCacheInvalidation;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private static final Duration ENROLLMENT_LIFETIME = Duration.ofMinutes(10);

  private final UserRepository users;
  private final AccountRepository accounts;
  private final AuditLogRepository audits;
  private final PasswordEncoder passwords;
  private final TotpService totp;
  private final TotpThrottle totpThrottle;
  private final RefreshTokenRepository refreshTokens;
  private final TotpSecretCustody custody;
  private final LoginChallengeRepository challenges;
  private final LoginChallengeService challengeService;
  private final TotpEnrollmentRepository enrollments;
  private final AccountLoginThrottle loginThrottle;
  private final Clock clock;
  private final LedgerCacheInvalidation invalidation;

  public AuthService(
      UserRepository users,
      AccountRepository accounts,
      AuditLogRepository audits,
      PasswordEncoder passwords,
      TotpService totp,
      TotpThrottle totpThrottle,
      RefreshTokenRepository refreshTokens,
      TotpSecretCustody custody,
      LoginChallengeRepository challenges,
      LoginChallengeService challengeService,
      TotpEnrollmentRepository enrollments,
      AccountLoginThrottle loginThrottle,
      Clock clock,
      LedgerCacheInvalidation invalidation) {
    this.users = users;
    this.accounts = accounts;
    this.audits = audits;
    this.passwords = passwords;
    this.totp = totp;
    this.totpThrottle = totpThrottle;
    this.refreshTokens = refreshTokens;
    this.custody = custody;
    this.challenges = challenges;
    this.challengeService = challengeService;
    this.enrollments = enrollments;
    this.loginThrottle = loginThrottle;
    this.clock = clock;
    this.invalidation = invalidation;
  }

  @Transactional
  public User register(String email, String rawPassword, String fullName) {
    String normalized = email.trim().toLowerCase();
    if (users.existsByEmail(normalized)) {
      throw new EmailTakenException(normalized);
    }
    User user =    users.save(new User(normalized, passwords.encode(rawPassword), fullName.trim()));
    Account checking = new Account(user.getId(),
        Iban.uniqueOrThrow(accounts::existsByIban, 5), AccountType.CHECKING);
    checking.setCreatedAt(clock.instant());
    accounts.save(checking);
    audits.save(AuditLog.of(user.getId(), "USER_REGISTERED", "User", user.getId().toString(),
        "email", user.getEmail()));
    invalidation.clearSynchronized("public-stats");
    return user;
  }

  @Transactional(readOnly = true)
  public User login(String email, String rawPassword) {
    String normalized = email.trim().toLowerCase();
    // Account-level budget on top of the per-IP limiter (F03). Keyed on the
    // normalized email whether or not it exists, so the answer never reveals
    // whether an account is real.
    loginThrottle.verifyAvailable(normalized);
    User user = users.findByEmail(normalized)
        .orElseThrow(() -> {
          loginThrottle.recordFailure(normalized);
          return new BadCredentialsException("Invalid email or password");
        });
    if (!passwords.matches(rawPassword, user.getPasswordHash())) {
      loginThrottle.recordFailure(normalized);
      throw new BadCredentialsException("Invalid email or password");
    }
    loginThrottle.recordSuccess(normalized);
    return user;
  }

  /**
   * F30 verification of a persisted login challenge (see {@link #verifyMfaChallenge}).
   *
   * The attempt is RESERVED atomically before any code is checked (section 9): one conditional UPDATE books it only while the challenge is
   * unused, unexpired and under its budget, committing in a short REQUIRES_NEW
   * boundary so a concurrent burst can never overshoot the five-attempt limit
   * and a rejected code can never erase its own booking. The final consume is
   * a second atomic UPDATE on the same conditions, so exactly one correct
   * submission wins the race.
   */
  @Transactional
  public User verifyMfaChallenge(UUID challengeId, String code) {
    // Reserve the attempt FIRST (may throw TooMany when the budget is spent,
    // or BadCredentials when the challenge is gone/consumed/expired). No row
    // lock is held across this call - the reservation commits on its own.
    challengeService.reserveAttempt(challengeId);
    LoginChallenge challenge = challenges.findById(challengeId)
        .orElseThrow(() -> new BadCredentialsException("Invalid MFA token"));
    User user = users.findById(challenge.getUserId())
        .orElseThrow(() -> new BadCredentialsException("Invalid MFA token"));
    totpThrottle.verifyAvailable(user.getEmail());
    if (!user.isTotpEnabled() || !totp.verify(secretOf(user), code)) {
      // The attempt was already booked by the reservation; the account-level
      // throttle records the failure so repeated guessing also locks the
      // account across freshly issued challenges.
      totpThrottle.recordFailure(user.getEmail());
      throw new BadCredentialsException("Invalid code");
    }
    if (challenges.consume(challengeId, clock.instant()) == 0) {
      throw new BadCredentialsException("Invalid MFA token");
    }
    totpThrottle.recordSuccess(user.getEmail());
    return user;
  }

  // -------------------------------------------------------------------------
  // F02: the TOTP lifecycle. Starting a setup NEVER touches the active factor:
  // a fresh secret lives in a pending enrollment and is promoted only after
  // the new authenticator verifies. Replacing or disabling an ACTIVE factor
  // requires a recent password plus a code from the EXISTING factor - never
  // mere possession of a bearer token. Promotion/disabling bumps the security
  // version and revokes refresh tokens, so old credentials die immediately.
  // -------------------------------------------------------------------------

  /**
   * Begins (or restarts) a pending enrollment for a NEW secret. Cancelling or
   * abandoning it leaves any active factor untouched.
   */
  @Transactional
  public String startTotpSetup(String email) {
    User user = userOf(email);
    enrollments.purgeFinished(user.getId(), clock.instant());
    String secret = totp.newSecret();
    String stored = storeForCustody(user.getId(), secret);
    enrollments.save(new TotpEnrollment(user.getId(), stored,
        custody.isActive() ? 1 : 0, clock.instant().plus(ENROLLMENT_LIFETIME)));
    audits.save(metadataAudit(user, "TOTP_SETUP_STARTED"));
    return secret;
  }

  /**
   * Verifies the pending enrollment and promotes it. When an active factor
   * exists this is a REPLACEMENT and additionally requires the current
   * password plus a valid code from the EXISTING authenticator - an ordinary
   * session alone can never swap a victim's factor.
   */
  @Transactional
  public User enableTotp(String email, String code, String currentPassword, String currentCode) {
    User user = userOf(email);
    TotpEnrollment pending = enrollments.findUsable(user.getId(), clock.instant()).stream()
        .findFirst()
        .orElseThrow(() -> new BadCredentialsException("No pending TOTP setup"));
    totpThrottle.verifyAvailable(email);

    boolean replacing = user.isTotpEnabled();
    if (replacing) {
      // Reauthentication for a factor change (OWASP MFA cheat sheet): the
      // caller must know the password AND still hold the existing factor.
      if (!passwords.matches(nullToEmpty(currentPassword), user.getPasswordHash())) {
        throw new BadCredentialsException("Invalid credentials");
      }
      if (!totp.verify(secretOf(user), currentCode)) {
        totpThrottle.recordFailure(email);
        throw new BadCredentialsException("Invalid code");
      }
    }
    String pendingSecret = loadForCustody(user.getId(), pending);
    if (!totp.verify(pendingSecret, code)) {
      totpThrottle.recordFailure(email);
      throw new BadCredentialsException("Invalid code");
    }
    totpThrottle.recordSuccess(email);

    storeSecret(user, pendingSecret);
    user.setTotpEnabled(true);
    // Immediate revocation: bump the version so every previously minted access
    // token fails validation, and revoke refresh rows so nothing can silently
    // mint successors.
    user.setSecurityVersion(user.getSecurityVersion() + 1);
    users.save(user);
    pending.setConsumed(true);
    enrollments.save(pending);
    audits.save(metadataAudit(user, replacing ? "TOTP_REPLACED" : "TOTP_ENABLED"));
    refreshTokens.revokeAllByUserId(user.getId());
    return user;
  }

  /** Abandons the pending enrollment; an active factor (if any) is unchanged. */
  @Transactional
  public void cancelTotpSetup(String email) {
    User user = userOf(email);
    for (TotpEnrollment pending : enrollments.findUsable(user.getId(), clock.instant())) {
      pending.setConsumed(true);
      enrollments.save(pending);
    }
    enrollments.purgeFinished(user.getId(), clock.instant());
    audits.save(metadataAudit(user, "TOTP_SETUP_CANCELLED"));
  }

  /**
   * Disables MFA. Like a replacement, this requires the current password AND a
   * valid code from the active authenticator (recent reauthentication + factor
   * proof); a stolen bearer token alone cannot remove the factor.
   */
  @Transactional
  public User disableTotp(String email, String password, String code) {
    User user = userOf(email);
    totpThrottle.verifyAvailable(email);
    if (!user.isTotpEnabled()) {
      throw new BadCredentialsException("MFA is not enabled");
    }
    if (!passwords.matches(nullToEmpty(password), user.getPasswordHash())) {
      throw new BadCredentialsException("Invalid credentials");
    }
    if (!totp.verify(secretOf(user), code)) {
      totpThrottle.recordFailure(email);
      throw new BadCredentialsException("Invalid code");
    }
    totpThrottle.recordSuccess(email);
    user.setTotpEnabled(false);
    clearSecret(user);
    user.setSecurityVersion(user.getSecurityVersion() + 1);
    users.save(user);
    audits.save(metadataAudit(user, "TOTP_DISABLED"));
    refreshTokens.revokeAllByUserId(user.getId());
    return user;
  }

  // --- secret custody helpers (F30) ------------------------------------------

  String secretOf(User user) {
    if (user.getTotpKeyVersion() >= 1) {
      return custody.decrypt(user.getId(), user.getTotpSecretCiphertext());
    }
    return user.getTotpSecret();
  }

  private String storeForCustody(UUID userId, String plaintext) {
    return custody.isActive() ? custody.encrypt(userId, plaintext) : plaintext;
  }

  private String loadForCustody(UUID userId, TotpEnrollment enrollment) {
    if (enrollment.getPendingKeyVersion() >= 1) {
      return custody.decrypt(userId, enrollment.getPendingSecret());
    }
    return enrollment.getPendingSecret();
  }

  private void storeSecret(User user, String plaintext) {
    if (plaintext == null) {
      clearSecret(user);
      return;
    }
    if (custody.isActive()) {
      user.setTotpSecretCiphertext(custody.encrypt(user.getId(), plaintext));
      user.setTotpKeyVersion(1);
      user.setTotpSecret(null);
    } else {
      user.setTotpSecret(plaintext);
      user.setTotpKeyVersion(0);
      user.setTotpSecretCiphertext(null);
    }
  }

  private void clearSecret(User user) {
    user.setTotpSecret(null);
    user.setTotpSecretCiphertext(null);
    user.setTotpKeyVersion(0);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
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
