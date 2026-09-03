package com.bank.platform.common;

import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.beneficiaries.BeneficiaryExistsException;
import com.bank.platform.beneficiaries.BeneficiaryNotFoundException;
import com.bank.platform.ledger.TransactionNotFoundException;
import com.bank.platform.auth.EmailTakenException;
import com.bank.platform.ledger.InsufficientFundsException;
import com.bank.platform.ledger.TransferValidationException;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> unreadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
    Throwable cause = ex.getMostSpecificCause();
    return problem(HttpStatus.BAD_REQUEST, "Malformed Request",
        cause == null ? "Malformed request" : cause.getMessage());
  }

  @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Bad Request",
        "Parameter \u0027" + ex.getName() + "\u0027 has an invalid value");
  }

  @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
  public ResponseEntity<Map<String, Object>> constraint(jakarta.validation.ConstraintViolationException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Validation Failed", ex.getMessage());
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<Map<String, Object>> noRoute(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", "No such endpoint");
  }

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

  @ExceptionHandler(BeneficiaryExistsException.class)
  public ResponseEntity<Map<String, Object>> beneficiaryConflict(BeneficiaryExistsException ex) {
    return problem(HttpStatus.CONFLICT, "Beneficiary Exists", ex.getMessage());
  }

  @ExceptionHandler(TransactionNotFoundException.class)
  public ResponseEntity<Map<String, Object>> transactionNotFound(TransactionNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }

  @ExceptionHandler(BeneficiaryNotFoundException.class)
  public ResponseEntity<Map<String, Object>> beneficiaryNotFound(BeneficiaryNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }

  @ExceptionHandler(AccountNotFoundException.class)
  public ResponseEntity<Map<String, Object>> notFound(AccountNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }

  @ExceptionHandler(InsufficientFundsException.class)
  public ResponseEntity<Map<String, Object>> unprocessable(InsufficientFundsException ex) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient Funds", ex.getMessage());
  }

  @ExceptionHandler(TransferValidationException.class)
  public ResponseEntity<Map<String, Object>> transferValidation(TransferValidationException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Transfer Rejected", ex.getMessage());
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, Object>> forbidden(AccessDeniedException ex) {
    return problem(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage());
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

