package com.bank.platform.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {

  private AuthDtos() {}

  // BCrypt truncates at 72 bytes, so the cap is enforced on UTF-8 byte length
  // (see PasswordBytes) rather than on characters - a multibyte password that
  // fits in 72 characters can still exceed 72 bytes.
  private static final int PASSWORD_MAX_BYTES = 72;

  public record RegisterRequest(
      @Email @NotBlank String email,
      @NotBlank @Size(min = 8) @PasswordBytes(max = PASSWORD_MAX_BYTES) String password,
      @NotBlank @Size(max = 255) String fullName) {}

  public record LoginRequest(
      @Email @NotBlank String email,
      @NotBlank @PasswordBytes(max = PASSWORD_MAX_BYTES) String password) {}

  public record UserResponse(UUID id, String email, String fullName, String role, boolean totpEnabled) {
    public static UserResponse from(User user) {
      return new UserResponse(
          user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(), user.isTotpEnabled());
    }
  }

  public record AuthResponse(
      String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {}

  // TOTP setup/enable/disable + the login challenge (202) share this shape.
  public record MfaRequiredResponse(String mfaToken, String message) {}

  public record MfaVerifyRequest(@NotBlank String mfaToken, @NotBlank String code) {}

  public record TotpCodeRequest(@NotBlank String code) {}

  public record TotpSetupResponse(String secret, String qrDataUri) {}
}
