package com.bank.platform.auth;

import com.bank.platform.auth.AuthDtos.AuthResponse;
import com.bank.platform.auth.AuthDtos.LoginRequest;
import com.bank.platform.auth.AuthDtos.RegisterRequest;
import com.bank.platform.auth.AuthDtos.UserResponse;
import com.bank.platform.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.CookieValue;
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
  private final RefreshService refreshService;
  private final UserRepository users;

  public AuthController(
      AuthService authService,
      JwtService jwtService,
      RefreshService refreshService,
      UserRepository users) {
    this.authService = authService;
    this.jwtService = jwtService;
    this.refreshService = refreshService;
    this.users = users;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(
      @Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
    User user = authService.register(request.email(), request.password(), request.fullName());
    return withRefresh(user, response);
  }

  @PostMapping("/login")
  public AuthResponse login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    User user = authService.login(request.email(), request.password());
    return withRefresh(user, response);
  }

  @PostMapping("/refresh")
  public AuthResponse refresh(
      @CookieValue(name = RefreshService.COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    if (refreshToken == null) {
      throw new BadCredentialsException("Missing refresh token");
    }
    RefreshService.TokenPair pair = refreshService.rotate(refreshToken);
    response.addHeader(HttpHeaders.SET_COOKIE,
        refreshCookie(pair.refreshToken(), RefreshService.COOKIE_MAX_AGE));
    return toAuthResponse(pair);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @CookieValue(name = RefreshService.COOKIE, required = false) String refreshToken,
      HttpServletResponse response) {
    refreshService.logout(refreshToken);
    response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", 0));
  }

  @GetMapping("/me")
  public UserResponse me(Authentication authentication) {
    return users
        .findByEmail(authentication.getName())
        .map(UserResponse::from)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  private AuthResponse withRefresh(User user, HttpServletResponse response) {
    RefreshService.TokenPair pair = refreshService.issue(user);
    response.addHeader(HttpHeaders.SET_COOKIE,
        refreshCookie(pair.refreshToken(), RefreshService.COOKIE_MAX_AGE));
    return toAuthResponse(pair);
  }

  private AuthResponse toAuthResponse(RefreshService.TokenPair pair) {
    User user = pair.user();
    return new AuthResponse(pair.accessToken(), "Bearer",
        jwtService.getAccessMinutes() * 60, UserResponse.from(user));
  }

  private String refreshCookie(String value, long maxAge) {
    return RefreshService.COOKIE + "=" + value
        + "; Path=/api/v1/auth; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax";
  }
}
