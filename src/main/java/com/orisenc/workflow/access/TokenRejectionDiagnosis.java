package com.orisenc.workflow.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out <em>which</em> token check failed, for the log.
 *
 * <p>The shared validator answers all five of its checks with one constant error, "The access token
 * is invalid". That is the right answer to give an unauthenticated caller and the wrong one to leave
 * in a log file: a wrong audience, a wrong tenant, a v1.0 token and a token with no scopes are four
 * different configuration mistakes with four different fixes, and they are indistinguishable from the
 * outside. Every service in the platform will meet this once, as it gets its own Entra registration.
 *
 * <p>Pure and side-effect free so it can be tested directly. It reports identifiers - tenant, audience,
 * client - which are not secrets, and never the token, a signature or a user claim.
 */
public final class TokenRejectionDiagnosis {

  private TokenRejectionDiagnosis() {}

  /**
   * A one-line explanation of why a token would be refused, or {@code null} when these claims look
   * acceptable.
   *
   * <p>Returning null matters: a rejection this cannot explain is a signature, issuer or expiry
   * failure handled before these checks, and claiming otherwise would send someone after the wrong
   * fix. The caller says so plainly in that case.
   */
  public static String explain(Map<String, Object> claims, String expectedTenantId,
      Set<String> acceptedAudiences, Set<String> allowedClientApplicationIds) {
    if (claims == null || claims.isEmpty()) return "the token carried no readable claims";

    String tenant = text(claims.get("tid"));
    if (expectedTenantId != null && !expectedTenantId.equals(tenant))
      return "tenant mismatch: token tid=" + display(tenant)
          + ", this service is configured for orisenc.security.tenant-id=" + display(expectedTenantId);

    String version = text(claims.get("ver"));
    if (!"2.0".equals(version))
      return "token version " + display(version)
          + " is not accepted; request a v2.0 token (the app registration's manifest sets accessTokenAcceptedVersion)";

    List<String> audiences = list(claims.get("aud"));
    if (acceptedAudiences != null && !acceptedAudiences.isEmpty()
        && audiences.stream().noneMatch(acceptedAudiences::contains))
      return "audience mismatch: token aud=" + display(String.join(", ", audiences))
          + ", this service accepts " + String.join(", ", acceptedAudiences)
          + " - set orisenc.security.client-id to the API registration the caller requested a scope for";

    String callingClient = text(claims.get("azp"));
    if (allowedClientApplicationIds != null && !allowedClientApplicationIds.isEmpty()
        && (callingClient == null || !allowedClientApplicationIds.contains(callingClient)))
      return "calling application " + display(callingClient) + " is not in "
          + "orisenc.security.allowed-client-application-ids=" + String.join(", ", allowedClientApplicationIds);

    String scopes = text(claims.get("scp"));
    List<String> roles = list(claims.get("roles"));
    if ((scopes == null || scopes.isBlank()) && roles.isEmpty())
      return "the token carries neither a scp claim nor app roles, so it grants nothing";

    return null;
  }

  private static String text(Object value) { return value == null ? null : String.valueOf(value); }

  @SuppressWarnings("unchecked")
  private static List<String> list(Object value) {
    if (value == null) return List.of();
    if (value instanceof Collection<?> collection) {
      var values = new ArrayList<String>();
      for (Object entry : collection) if (entry != null) values.add(String.valueOf(entry));
      return List.copyOf(values);
    }
    return List.of(String.valueOf(value));
  }

  private static String display(String value) {
    return value == null || value.isBlank() ? "(absent)" : value;
  }
}
