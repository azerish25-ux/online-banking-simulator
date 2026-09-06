package com.bank.platform.common;

/**
 * The one error body (RFC-7807 shape) every failure answers with. Controllers,
 * the security chain and the advice all produce this exact record so the
 * problem schema cannot drift between responses, and the OpenAPI contract can
 * name it once. {@code detail} is never an internal stack trace; unknown
 * failures answer a generic line while the real cause goes to the operator
 * log under the request's trace id.
 */
public record ApiProblem(
    String type, String title, int status, String detail, String timestamp) {}
