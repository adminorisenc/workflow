package com.orisenc.workflow.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Renders errors as {@code {"message": "..."}} to match the contract the frozen UI already parses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, String>> handle(ApiException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of("message", exception.getMessage()));
  }

  /**
   * Thrown when a {@code @PreAuthorize} check fails. Mapped explicitly so a permission failure
   * returns the same body shape as every other error and preserves the established insufficient
   * permission message.
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, String>> handle(AccessDeniedException exception) {
    return ResponseEntity.status(403).body(Map.of("message", "Your role does not permit this action."));
  }
}
