package com.orisenc.workflow.api;

import org.springframework.http.HttpStatus;

/**
 * Application error carrying an HTTP status and a human-readable message.
 *
 * <p>Exists rather than using Spring's {@code ResponseStatusException} so the response body stays
 * exactly {@code {"message": "..."}}, which is the established UI contract. Spring's default
 * error body is a different structure and would be a breaking change.
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;

  public ApiException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  public HttpStatus status() {
    return status;
  }

  public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
  public static ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }
  public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
  public static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, message); }
  public static ApiException serviceUnavailable(String message) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, message); }
}
