package com.bank.platform.auth;

import com.bank.platform.auth.AuthDtos.AuthResponse;
import com.bank.platform.auth.AuthDtos.LoginRequest;
import com.bank.platform.auth.AuthDtos.RegisterRequest;
import com.bank.platform.auth.AuthDtos.UserResponse;
import com.bank.platform.security.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
  private final TotpThrottle totpThrottle;
  private final UserRepository users;
  private final boolean cookieSecure;

  public AuthController(
      AuthService authService,
      JwtService jwtService,
      RefreshService refreshService,
      TotpService totpService,
      TotpThrottle totpThrottle,
      UserRepository users,
      @Value("${app.cookie.secure:false}") boolean cookieSecure) {
    this.authService = authService;
    this.jwtService = jwtService;
    this.refreshService = refreshService;
    this.totpService = totpService;
    this.totpThrottle = totpThrottle;
    this.users = users;
    this.cookieSecure = cookieSecure;
  }

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
          .body(new AuthDtos.MfaRequiredResponse(jwtService.generateMfa(user.getEmail()), "MFA_REQUIRED"));
    }
    return ResponseEntity.ok(withRefresh(user, response));
  }

  @PostMapping("/mfa/verify")
  public AuthResponse mfaVerify(
      @Valid @RequestBody AuthDtos.MfaVerifyRequest request, HttpServletResponse response) {
    String email;
    try {
      email = jwtService.requireMfaSubject(request.mfaToken());
    } catch (JwtException ex) {
      throw new BadCredentialsException("Invalid MFA token");
    }
    User user = users.findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    // The per-IP auth limiter also guards this path, but the challenge token is
    // proof the caller already knows the password - a per-account budget stops
    // that caller from brute-forcing the six-digit code at network speed.
    totpThrottle.verifyAvailable(email);
    if (!user.isTotpEnabled() || !totpService.verify(user.getTotpSecret(), request.code())) {
      totpThrottle.recordFailure(email);
      throw new BadCredentialsException("Invalid code");
    }
    totpThrottle.recordSuccess(email);
    return withRefresh(user, response);
  }

  @PostMapping("/totp/setup")
  public AuthDtos.TotpSetupResponse totpSetup(Authentication authentication) {
    String email = authentication.getName();
    String secret = authService.startTotpSetup(email);
    return new AuthDtos.TotpSetupResponse(secret, totpService.qrDataUri(totpService.otpauthUri(email, secret)));
  }

  @PostMapping("/totp/enable")
  public UserResponse totpEnable(
      Authentication authentication, @Valid @RequestBody AuthDtos.TotpCodeRequest request) {
    return UserResponse.from(authService.enableTotp(authentication.getName(), request.code()));
  }

  @PostMapping("/totp/disable")
  public UserResponse totpDisable(
      Authentication authentication, @Valid @RequestBody AuthDtos.TotpCodeRequest request) {
    return UserResponse.from(authService.disableTotp(authentication.getName(), request.code()));
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
        jwtService.getAccessSeconds(), UserResponse.from(user));
  }

  /**
   * The browser never calls the backend directly: every request goes through
   * the Next.js rewrite proxy at /backend/*, so the cookie must be scoped to
   * the path the browser actually requests. Scoping it to /api/v1/auth (the
   * backend's own route) would mean the cookie never leaves the browser and
   * every session would die at access-token expiry.
   */
  private String refreshCookie(String value, long maxAge) {
    String cookie = RefreshService.COOKIE + "=" + value
        + "; Path=/backend/v1/auth; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax";
    return cookieSecure ? cookie + "; Secure" : cookie;
  }
}
