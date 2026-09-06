package com.bank.platform.auth;

import com.bank.platform.auth.AuthDtos.AuthResponse;
import com.bank.platform.auth.AuthDtos.LoginRequest;
import com.bank.platform.auth.AuthDtos.RegisterRequest;
import com.bank.platform.auth.AuthDtos.UserResponse;
import com.bank.platform.common.ApiProblem;
import com.bank.platform.security.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;
  private final JwtService jwtService;
  private final RefreshService refreshService;
  private final LoginChallengeService challengeService;
  private final TotpService totpService;
  private final UserRepository users;
  private final boolean cookieSecure;

  public AuthController(
      AuthService authService,
      JwtService jwtService,
      RefreshService refreshService,
      LoginChallengeService challengeService,
      TotpService totpService,
      UserRepository users,
      @Value("${app.cookie.secure:false}") boolean cookieSecure) {
    this.authService = authService;
    this.jwtService = jwtService;
    this.refreshService = refreshService;
    this.challengeService = challengeService;
    this.totpService = totpService;
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

  /**
   * Login has two DISTINCT success outcomes, and the contract names both with
   * their real status codes: 200 carries the authenticated session
   * ({@link AuthResponse}) and 202 carries the single-use MFA challenge
   * ({@link MfaRequiredResponse}) when the account has a factor enabled. The
   * wildcard return type is annotated because it genuinely returns both;
   * without the explicit responses the spec would describe a shapeless 200.
   */
  @Operation(summary = "Log in", description = "Returns 200 with an authenticated session, or 202 with a single-use MFA challenge when the account has a factor enabled.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Authenticated - session tokens issued",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthResponse.class))),
      @ApiResponse(responseCode = "202", description = "MFA required - verify the returned mfaToken",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthDtos.MfaRequiredResponse.class))),
      @ApiResponse(responseCode = "400", description = "Invalid credentials or validation failure",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiProblem.class))),
      @ApiResponse(responseCode = "401", description = "Wrong email or password",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiProblem.class))),
      @ApiResponse(responseCode = "429", description = "Login attempts throttled",
          content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiProblem.class)))
  })
  @PostMapping("/login")
  public ResponseEntity<?> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    User user = authService.login(request.email(), request.password());
    if (user.isTotpEnabled()) {
      // F30: the returned challenge is a PERSISTED row; the token's subject is
      // the challenge's random id (never the email), so a challenge cannot be
      // re-targeted and its use is single-shot.
      LoginChallenge challenge = challengeService.issue(user);
      return ResponseEntity.status(HttpStatus.ACCEPTED)
          .body(new AuthDtos.MfaRequiredResponse(
              jwtService.generateMfa(challenge.getId().toString()), "MFA_REQUIRED"));
    }
    return ResponseEntity.ok(withRefresh(user, response));
  }

  @PostMapping("/mfa/verify")
  public AuthResponse mfaVerify(
      @Valid @RequestBody AuthDtos.MfaVerifyRequest request, HttpServletResponse response) {
    UUID challengeId;
    try {
      challengeId = UUID.fromString(jwtService.requireMfaSubject(request.mfaToken()));
    } catch (JwtException | IllegalArgumentException ex) {
      throw new BadCredentialsException("Invalid MFA token");
    }
    User user = authService.verifyMfaChallenge(challengeId, request.code());
    return withRefresh(user, response);
  }

  @PostMapping("/totp/setup")
  public AuthDtos.TotpSetupResponse totpSetup(Authentication authentication) {
    String email = authentication.getName();
    String secret = authService.startTotpSetup(email);
    return new AuthDtos.TotpSetupResponse(secret, totpService.qrDataUri(totpService.otpauthUri(email, secret)));
  }

  /**
   * Cancel the pending enrollment without touching an active factor. Ordinary
   * sessions may always abandon their OWN pending setup.
   */
  @PostMapping("/totp/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void totpCancel(Authentication authentication) {
    authService.cancelTotpSetup(authentication.getName());
  }

  /**
   * Promote the pending enrollment. When an active factor exists this is a
   * replacement and requires the current password + existing-factor code
   * (F02); an ordinary bearer token alone can never swap the factor.
   */
  /**
   * Enabling (or replacing) the factor bumps the user's security version and
   * revokes every outstanding refresh token (F02) - which would invalidate
   * THIS session's access token on its very next call. So the response
   * reissues a fresh credential pair under the NEW version: the caller stays
   * signed in across the factor change and the old credentials stay dead.
   */
  @PostMapping("/totp/enable")
  public AuthResponse totpEnable(
      Authentication authentication, @Valid @RequestBody AuthDtos.TotpEnableRequest request,
      HttpServletResponse response) {
    User user = authService.enableTotp(authentication.getName(),
        request.code(), request.currentPassword(), request.currentCode());
    return withRefresh(user, response);
  }

  /**
   * Disable MFA: requires password + a valid code from the ACTIVE factor, so a
   * stolen session cannot silently remove the user's protection. Like enable,
   * the factor change bumps the security version, so a fresh credential pair
   * is issued under the new version to keep the current session alive.
   */
  @PostMapping("/totp/disable")
  public AuthResponse totpDisable(
      Authentication authentication, @Valid @RequestBody AuthDtos.TotpDisableRequest request,
      HttpServletResponse response) {
    User user = authService.disableTotp(authentication.getName(), request.password(), request.code());
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
        refreshCookie(pair.refreshToken(), refreshService.cookieMaxAgeSeconds()));
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
        refreshCookie(pair.refreshToken(), refreshService.cookieMaxAgeSeconds()));
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
