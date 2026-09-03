package com.bank.platform.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {

  private AuthDtos() {}

  public record RegisterRequest(
      @Email @NotBlank String email,
      @Size(min = 8, max = 100) String password,
      @NotBlank @Size(max = 255) String fullName) {}

  public record LoginRequest(
      @Email @NotBlank String email,
      @NotBlank String password) {}

  public record UserResponse(UUID id, String email, String fullName, String role) {
    public static UserResponse from(User user) {
      return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
  }

  public record AuthResponse(
      String accessToken, String tokenType, long expiresInSeconds, UserResponse user) {}
}
