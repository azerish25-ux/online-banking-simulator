package com.bank.platform.auth;

import com.bank.platform.auth.AuthDtos.AuthResponse;
import com.bank.platform.auth.AuthDtos.LoginRequest;
import com.bank.platform.auth.AuthDtos.RegisterRequest;
import com.bank.platform.auth.AuthDtos.UserResponse;
import com.bank.platform.security.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
  private final TotpService totpService;
  private final UserRepository users;
  private final boolean cookieSecure;

  public AuthController(
      AuthService authService,
      JwtService jwtService,
      RefreshService refreshService,
      TotpService totpService,
      UserRepository users,
      @Value("${app.cookie.secure:false}") boolean cookieSecure) {
    this.authService = authService;
    this.jwtService = jwtService;
    this.refreshService = refreshService;
    this.totpService = totpService;
    this.users = users;
    this.cookieSecure = cookieSecure;
  }

  public record MfaRequiredResponse(String mfaToken, String message) {}
  public record MfaVerifyRequest(@NotBlank String mfaToken, @NotBlank String code) {}
  public record TotpCodeRequest(@NotBlank String code) {}
  public record TotpSetupResponse(String secret, String qrDataUri) {}

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(
      @Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
    User user = authService.register(request.email(), request.password(), request.fullName());
    return withRefresh(user, response);
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    User user = authService.login(request.email(), request.password());
    if (user.isTotpEnabled()) {
      return ResponseEntity.status(HttpStatus.ACCEPTED)
          .body(new MfaRequiredResponse(jwtService.generateMfa(user.getEmail()), "MFA_REQUIRED"));
    }
    return ResponseEntity.ok(withRefresh(user, response));
  }

  @PostMapping("/mfa/verify")
  public AuthResponse mfaVerify(
      @Valid @RequestBody MfaVerifyRequest request, HttpServletResponse response) {
    String email;
    try {
      email = jwtService.requireMfaSubject(request.mfaToken());
    } catch (JwtException ex) {
      throw new BadCredentialsException("Invalid MFA token");
    }
    User user = users.findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    if (!user.isTotpEnabled() || !totpService.verify(user.getTotpSecret(), request.code())) {
      throw new BadCredentialsException("Invalid code");
    }
    return withRefresh(user, response);
  }

  @PostMapping("/totp/setup")
  public TotpSetupResponse totpSetup(Authentication authentication) {
    User user = userOf(authentication.getName());
    String secret = totpService.newSecret();
    user.setTotpSecret(secret);
    users.save(user);
    return new TotpSetupResponse(secret, totpService.qrDataUri(totpService.otpauthUri(user.getEmail(), secret)));
  }

  @PostMapping("/totp/enable")
  public UserResponse totpEnable(
      Authentication authentication, @Valid @RequestBody TotpCodeRequest request) {
    User user = userOf(authentication.getName());
    if (user.getTotpSecret() == null || !totpService.verify(user.getTotpSecret(), request.code())) {
      throw new BadCredentialsException("Invalid code");
    }
    user.setTotpEnabled(true);
    users.save(user);
    return UserResponse.from(user);
  }

  @PostMapping("/totp/disable")
  public UserResponse totpDisable(
      Authentication authentication, @Valid @RequestBody TotpCodeRequest request) {
    User user = userOf(authentication.getName());
    if (!user.isTotpEnabled() || !totpService.verify(user.getTotpSecret(), request.code())) {
      throw new BadCredentialsException("Invalid code");
    }
    user.setTotpEnabled(false);
    user.setTotpSecret(null);
    users.save(user);
    return UserResponse.from(user);
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
    return UserResponse.from(userOf(authentication.getName()));
  }

  private User userOf(String email) {
    return users.findByEmail(email)
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
    String cookie = RefreshService.COOKIE + "=" + value
        + "; Path=/api/v1/auth; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax";
    return cookieSecure ? cookie + "; Secure" : cookie;
  }
}
