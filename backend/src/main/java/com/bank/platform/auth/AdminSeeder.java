package com.bank.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Ensures an operator account exists. Credentials come from the environment in
 * real deployments; the built-in default is for local demos only and is loudly warned about.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final String email;
  private final String password;

  public AdminSeeder(
      UserRepository users,
      PasswordEncoder passwords,
      @Value("${app.admin.email:admin@bank.local}") String email,
      @Value("${app.admin.password:change-me-admin-123}") String password) {
    this.users = users;
    this.passwords = passwords;
    this.email = email.trim().toLowerCase();
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (users.existsByEmail(email)) {
      return;
    }
    users.save(new User(email, passwords.encode(password), "Bank Operator", Role.ADMIN));
    if (password.equals("change-me-admin-123")) {
      log.warn("ADMIN SEEDED with DEFAULT password for {}: set APP_ADMIN_PASSWORD in real environments!", email);
    } else {
      log.info("Admin account ensured for {}", email);
    }
  }
}

