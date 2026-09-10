package com.bank.platform.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(name = "password_hash", nullable = false, columnDefinition = "TEXT")
  private String passwordHash;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Column(nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private Role role = Role.CUSTOMER;

  @Column(name = "totp_secret", length = 64)
  private String totpSecret;

  // Secret custody: when a master key is configured, the usable seed is
  // stored encrypted in totp_secret_ciphertext (key version >= 1) and the
  // legacy totp_secret column is cleared. Version 0 rows are pre-migration
  // plaintext that the startup migrator converts once a key is supplied.
  @Column(name = "totp_secret_ciphertext", length = 512)
  private String totpSecretCiphertext;

  @Column(name = "totp_key_version", nullable = false)
  private int totpKeyVersion;

  /**
   * Bumped on every factor change. Access tokens carry the version they were
   * minted under and the auth filter rejects stale ones, so an old access
   * token stops working the moment a factor changes.
   */
  @Column(name = "security_version", nullable = false)
  private int securityVersion;

  @Column(name = "totp_enabled", nullable = false)
  private boolean totpEnabled;

  /**
   * Optimistic-lock guard (V30): a writer that changed security state without
   * the per-user lock fails loudly on commit instead of silently interleaving
   * two factor transitions. The pessimistic lock remains the primary
   * serializer; this column only turns a lost update into an error.
   */
  @jakarta.persistence.Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected User() {}

  public User(String email, String passwordHash, String fullName) {
    this(email, passwordHash, fullName, Role.CUSTOMER);
  }

  public User(String email, String passwordHash, String fullName, Role role) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.fullName = fullName;
    this.role = role;
  }

  @PreUpdate
  void touch() { updatedAt = Instant.now(); }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public String getEmail() { return email; }
  public String getPasswordHash() { return passwordHash; }
  public String getFullName() { return fullName; }
  public Role getRole() { return role; }
  public Instant getUpdatedAt() { return updatedAt; }
  public String getTotpSecret() { return totpSecret; }
  public void setTotpSecret(String v) { totpSecret = v; }
  public String getTotpSecretCiphertext() { return totpSecretCiphertext; }
  public void setTotpSecretCiphertext(String v) { totpSecretCiphertext = v; }
  public int getTotpKeyVersion() { return totpKeyVersion; }
  public void setTotpKeyVersion(int v) { totpKeyVersion = v; }
  public int getSecurityVersion() { return securityVersion; }
  public void setSecurityVersion(int v) { securityVersion = v; }
  public boolean isTotpEnabled() { return totpEnabled; }
  public void setTotpEnabled(boolean v) { totpEnabled = v; }
  public Instant getCreatedAt() { return createdAt; }
}

