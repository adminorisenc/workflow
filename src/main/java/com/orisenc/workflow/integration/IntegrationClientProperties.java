package com.orisenc.workflow.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for Workflow's daemon calls to the Integration Service.
 *
 * <p>Workflow raises notifications and never delivers one. Sending an email or a Teams message is a
 * third-party call, and blueprint decision 12 gives every third-party call to the Integration
 * Service - so this client posts an intention and returns.
 *
 * <p>Deliberately a copy of the Accounts client's properties rather than a shared library: each
 * service is an independent Gradle build with no common module, and the same reasoning that put a
 * copy of {@code SpringBeanConstructorTest} in each service applies here.
 */
@ConfigurationProperties("orisenc.integration-client")
public record IntegrationClientProperties(
    boolean enabled,
    String baseUrl,
    String clientId,
    String clientSecret,
    String scope,
    Duration requestTimeout,
    Duration tokenRefreshSkew) {

  public IntegrationClientProperties {
    baseUrl = trimTrailingSlash(baseUrl);
    clientId = trimToNull(clientId);
    clientSecret = trimToNull(clientSecret);
    scope = trimToNull(scope);
    // Ten seconds rather than Accounts' thirty: this client only ever posts one small notification,
    // never pulls a page of documents. A raise that has not answered in ten seconds is a route that
    // is down, and the SLA pass should move on to the next task rather than stall on it.
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    tokenRefreshSkew = tokenRefreshSkew == null ? Duration.ofMinutes(2) : tokenRefreshSkew;
  }

  /** Refuses an enabled client that could only fail later, inside a scheduled pass. */
  public void validateConfigured() {
    if (!enabled) return;
    if (baseUrl == null || clientId == null || clientSecret == null || scope == null) {
      throw new IllegalStateException("orisenc.integration-client is enabled but base-url, client-id, "
          + "client-secret or scope is missing.");
    }
    if (requestTimeout.isZero() || requestTimeout.isNegative())
      throw new IllegalStateException("orisenc.integration-client.request-timeout must be positive.");
    if (tokenRefreshSkew.isNegative())
      throw new IllegalStateException("orisenc.integration-client.token-refresh-skew cannot be negative.");
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String trimTrailingSlash(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? null : trimmed.replaceAll("/+$", "");
  }
}
