package com.bank.platform.security;

import com.bank.platform.auth.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final UserRepository users;

  public JwtAuthFilter(JwtService jwtService, UserRepository users) {
    this.jwtService = jwtService;
    this.users = users;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      String token = header.substring(7);
      try {
        // The ONLY trusted validation path for authenticated requests. An MFA
        // challenge token (purpose=mfa, mfa audience) is rejected here, so a
        // half-finished login can never call protected endpoints.
        JwtService.AccessToken parsed = jwtService.parseAccess(token);
        users.findByEmail(parsed.subject()).ifPresent(user -> {
          // A token minted before a factor change carries a stale security
          // version: reject it so old access credentials do not outlive the
          // revocation (F02). Authorities always come from the database row.
          if (user.getSecurityVersion() != parsed.securityVersion()) {
            return;
          }
          var auth = new UsernamePasswordAuthenticationToken(
              user.getEmail(), null,
              List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
          SecurityContextHolder.getContext().setAuthentication(auth);
        });
      } catch (JwtException | IllegalArgumentException ex) {
        // Malformed input must answer 401 through the security chain, never
        // escape the filter as a 500: jjwt raises IllegalArgumentException
        // (not JwtException) for empty or structurally broken tokens.
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(request, response);
  }
}
