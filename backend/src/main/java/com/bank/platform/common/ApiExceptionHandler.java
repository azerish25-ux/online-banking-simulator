package com.bank.platform.common;

import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.auth.AuthThrottledException;
import com.bank.platform.auth.EmailTakenException;
import com.bank.platform.auth.TooManyTotpAttemptsException;
import com.bank.platform.beneficiaries.BeneficiaryExistsException;
import com.bank.platform.beneficiaries.BeneficiaryNotFoundException;
import com.bank.platform.ledger.IdempotencyConflictException;
import com.bank.platform.ledger.InsufficientFundsException;
import com.bank.platform.ledger.OperationKeyAmbiguousException;
import com.bank.platform.ledger.TransactionNotFoundException;
import com.bank.platform.ledger.TransferValidationException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiProblem> unreadable(HttpMessageNotReadableException ex) {
    Throwable cause = ex.getMostSpecificCause();
    return problem(HttpStatus.BAD_REQUEST, "Malformed Request",
        cause == null ? "Malformed request" : cause.getMessage());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiProblem> typeMismatch(MethodArgumentTypeMismatchException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Bad Request",
        "Parameter \u0027" + ex.getName() + "\u0027 has an invalid value");
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiProblem> constraint(ConstraintViolationException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Validation Failed", ex.getMessage());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiProblem> noRoute(NoResourceFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", "No such endpoint");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiProblem> badRequest(IllegalArgumentException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiProblem> validation(MethodArgumentNotValidException ex) {
    String detail = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .collect(Collectors.joining("; "));
    return problem(HttpStatus.BAD_REQUEST, "Validation Failed", detail);
  }

  @ExceptionHandler(EmailTakenException.class)
  public ResponseEntity<ApiProblem> conflict(EmailTakenException ex) {
    return problem(HttpStatus.CONFLICT, "Email Taken", ex.getMessage());
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ApiProblem> unauthorized(BadCredentialsException ex) {
    return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage());
  }

  /**
   * An account burned its TOTP verification budget (code guessing defense).
   * 429 with Retry-After, same RFC-7807 shape as every other error.
   */
  @ExceptionHandler(TooManyTotpAttemptsException.class)
  public ResponseEntity<ApiProblem> totpThrottled(TooManyTotpAttemptsException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", "60")
        .body(ApiExceptionHandler.body(
            HttpStatus.TOO_MANY_REQUESTS, "Too Many Attempts", ex.getMessage()));
  }

  /** An account burned its password-login failure budget (F03). */
  @ExceptionHandler(AuthThrottledException.class)
  public ResponseEntity<ApiProblem> authThrottled(AuthThrottledException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", "900")
        .body(ApiExceptionHandler.body(
            HttpStatus.TOO_MANY_REQUESTS, "Too Many Attempts", ex.getMessage()));
  }

  @ExceptionHandler(BeneficiaryExistsException.class)
  public ResponseEntity<ApiProblem> beneficiaryConflict(BeneficiaryExistsException ex) {
    return problem(HttpStatus.CONFLICT, "Beneficiary Exists", ex.getMessage());
  }

  @ExceptionHandler(TransactionNotFoundException.class)
  public ResponseEntity<ApiProblem> transactionNotFound(TransactionNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }

  @ExceptionHandler(BeneficiaryNotFoundException.class)
  public ResponseEntity<ApiProblem> beneficiaryNotFound(BeneficiaryNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }

  @ExceptionHandler(AccountNotFoundException.class)
  public ResponseEntity<ApiProblem> notFound(AccountNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
  }

  @ExceptionHandler(InsufficientFundsException.class)
  public ResponseEntity<ApiProblem> unprocessable(InsufficientFundsException ex) {
    return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient Funds", ex.getMessage());
  }

  @ExceptionHandler(TransferValidationException.class)
  public ResponseEntity<ApiProblem> transferValidation(TransferValidationException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Transfer Rejected", ex.getMessage());
  }

  /**
   * One key presented with two different intents (F06): the key already names
   * an operation, and the new payload is not the one it names. 409 so the
   * client treats it as "resolve the original operation", not as a retryable
   * or rejected submit.
   */
  @ExceptionHandler(IdempotencyConflictException.class)
  public ResponseEntity<ApiProblem> idempotencyConflict(IdempotencyConflictException ex) {
    return problem(HttpStatus.CONFLICT, "Idempotency Conflict", ex.getMessage());
  }

  /**
   * A key-only recovery lookup matched several of the caller's own operations
   * on different originating accounts. Not a "pick the first row" situation:
   * the caller must scope the lookup to the originating account. 409 names
   * the ambiguity explicitly.
   */
  @ExceptionHandler(OperationKeyAmbiguousException.class)
  public ResponseEntity<ApiProblem> operationKeyAmbiguous(OperationKeyAmbiguousException ex) {
    return problem(HttpStatus.CONFLICT, "Ambiguous Operation Key", ex.getMessage());
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiProblem> forbidden(AccessDeniedException ex) {
    return problem(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage());
  }

  /**
   * DB constraint races (a unique/index collision two requests hit at once)
   * must not surface as a 500 with internals - they are conflicts.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiProblem> conflict(DataIntegrityViolationException ex) {
    return problem(HttpStatus.CONFLICT, "Conflict",
        "The request conflicts with existing data; try again with different values");
  }

  /**
   * A row changed after it was read (optimistic lock on accounts - see V16).
   * The other writer won; the request is safe to retry against fresh state.
   * Reaching this is always better than silently overwriting a newer balance.
   */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiProblem> staleWrite(ObjectOptimisticLockingFailureException ex) {
    return problem(HttpStatus.CONFLICT, "Conflict",
        "The resource changed concurrently; retry the request");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiProblem> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
    return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "Method not supported for this endpoint");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiProblem> unsupportedMedia(HttpMediaTypeNotSupportedException ex) {
    return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
        "Content-Type must be application/json");
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiProblem> missingParameter(MissingServletRequestParameterException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Bad Request",
        "Missing required parameter '" + ex.getParameterName() + "'");
  }

  /**
   * Last-resort guard: whatever escapes the specific handlers still answers
   * RFC-7807 with NO internals - never the exception message or stack trace.
   * The body stays generic, but the failure itself is always logged with the
   * request's trace id so operators can actually investigate it.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiProblem> unexpected(Exception ex) {
    log.error("Unhandled exception (requestId={})", MDC.get(TraceFilter.TRACE_ID), ex);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Error", null);
  }

  /**
   * The one RFC-7807 body. Controllers outside this advice's reach (e.g.
   * package-local not-found handlers) and the security chain's 401/403
   * entry point use it so the problem shape - including the type URI -
   * cannot drift between responses.
   */
  public static ApiProblem body(HttpStatus status, String title, String detail) {
    return new ApiProblem(
        "/problems/" + status.value(),
        title,
        status.value(),
        detail == null ? title : detail,
        Instant.now().toString());
  }

  public static ResponseEntity<ApiProblem> response(
      HttpStatus status, String title, String detail) {
    return ResponseEntity.status(status).body(body(status, title, detail));
  }

  private ResponseEntity<ApiProblem> problem(HttpStatus status, String title, String detail) {
    return response(status, title, detail);
  }
}

