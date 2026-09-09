package com.bank.platform.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The API promises RFC-7807 errors on every path. These handlers cannot be
 * reached through MockMvc easily (nothing throws them on purpose), so pin the
 * contract directly: status codes, the problem fields, and: for the catch-all
 *: that internals never leak into the body.
 */
class ApiExceptionHandlerTest {

  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void unhandledFailuresAnswerRfc7807WithoutLeakingInternals() {
    ResponseEntity<ApiProblem> response =
        handler.unexpected(new IllegalStateException("top-secret-internals"));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    ApiProblem body = response.getBody();
    assertNotNull(body);
    assertEquals("Internal Error", body.title());
    assertEquals(500, body.status());
    assertNotNull(body.timestamp());
    // The generic message must never surface: it may contain paths, SQL or
    // library details an attacker could exploit.
    assertFalse(String.valueOf(body.detail()).contains("top-secret-internals"),
        "detail must not echo the exception message");
    assertTrue(String.valueOf(body.type()).startsWith("/problems/"),
        "problem type must be a resolvable RFC-7807 URI reference");
  }

  @Test
  void dataIntegrityRacesAnswer409Not500() {
    ResponseEntity<ApiProblem> response =
        handler.conflict(new DataIntegrityViolationException("duplicate key value"));

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    ApiProblem body = response.getBody();
    assertNotNull(body);
    assertEquals("Conflict", body.title());
    assertEquals(409, body.status());
    assertFalse(String.valueOf(body.detail()).contains("duplicate key"),
        "raw constraint text must not leak into the detail");
  }
}
