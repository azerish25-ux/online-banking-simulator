package com.bank.platform.auth;

import com.bank.platform.auth.AuthDtos.AuthResponse;
import com.bank.platform.auth.AuthDtos.LoginRequest;
import com.bank.platform.auth.AuthDtos.RegisterRequest;
import com.bank.platform.auth.AuthDtos.UserResponse;
import com.bank.platform.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;
  private final JwtService jwtService;
  private final UserRepository users;

  public AuthController(AuthService authService, JwtService jwtService, UserRepository users) {
    this.authService = authService;
    this.jwtService = jwtService;
    this.users = users;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
    User user = authService.register(request.email(), request.password(), request.fullName());
    return toAuthResponse(user);
  }

  @PostMapping("/login")
  public AuthResponse login(@Valid @RequestBody LoginRequest request) {
    User user = authService.login(request.email(), request.password());
    return toAuthResponse(user);
  }

  @GetMapping("/me")
  public UserResponse me(Authentication authentication) {
    return users
        .findByEmail(authentication.getName())
        .map(UserResponse::from)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  private AuthResponse toAuthResponse(User user) {
    String token = jwtService.generate(user.getEmail(), user.getRole().name());
    return new AuthResponse(token, "Bearer", jwtService.getAccessMinutes() * 60, UserResponse.from(user));
  }
}
