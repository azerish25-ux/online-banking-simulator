package com.bank.platform.common;

import com.bank.platform.auth.EmailTakenException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
    String detail = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .collect(Collectors.joining("; "));
    return problem(HttpStatus.BAD_REQUEST, "Validation Failed", detail);
  }

  @ExceptionHandler(EmailTakenException.class)
  public ResponseEntity<Map<String, Object>> conflict(EmailTakenException ex) {
    return problem(HttpStatus.CONFLICT, "Email Taken", ex.getMessage());
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<Map<String, Object>> unauthorized(BadCredentialsException ex) {
    return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage());
  }

  private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String title, String detail) {
    return ResponseEntity.status(status).body(Map.of(
        "type", "https://bank.local/problems/" + status.value(),
        "title", title,
        "status", status.value(),
        "detail", detail == null ? title : detail,
        "timestamp", Instant.now().toString()));
  }
}
