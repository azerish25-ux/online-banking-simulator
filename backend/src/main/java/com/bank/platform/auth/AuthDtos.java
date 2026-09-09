package com.bank.platform.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {

  private AuthDtos() {}

  // BCrypt truncates at 72 bytes, so the cap is enforced on UTF-8 byte length
  // (see PasswordBytes) rather than on characters: a multibyte password that
  // fits in 72 characters can still exceed 72 bytes.
  private static final int PASSWORD_MAX_BYTES = 72;

  public record RegisterRequest(
      @Email @NotBlank String email,
      @NotBlank @Size(min = 8) @PasswordBytes(max = PASSWORD_MAX_BYTES) String password,
      @NotBlank @Size(max = 255) String fullName) {}

  public record LoginRequest(
      @Email @NotBlank String email,
      @NotBlank @PasswordBytes(max = PASSWORD_MAX_BYTES) String password) {}

  /** Role is the domain enum, serialized by name (legal values in the contract). */
  public record UserResponse(UUID id, String email, String fullName, Role role, boolean totpEnabled) {
    public static UserResponse from(User user) {
      return new UserResponse(
          user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.isTotpEnabled());
    }
  }

  public record AuthResponse(
      String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {}

  // TOTP setup/enable/disable + the login challenge (202) share this shape.
  public record MfaRequiredResponse(String mfaToken, String message) {}

  public record MfaVerifyRequest(@NotBlank String mfaToken, @NotBlank String code) {}

  /**
   * Enabling a NEW factor only needs the new code. REPLACING an active factor
   * additionally requires the current password and a code from the existing
   * authenticator (reauthentication boundary).
   */
  public record TotpEnableRequest(
      @NotBlank String code,
      String currentPassword,
      String currentCode) {}

  /** Disabling an active factor requires password + current factor code. */
  public record TotpDisableRequest(@NotBlank String password, @NotBlank String code) {}

  public record TotpSetupResponse(String secret, String qrDataUri) {}
}
