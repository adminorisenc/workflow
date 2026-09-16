package com.orisenc.workflow.delegation;

import com.orisenc.security.OrisencAuthorizationProperties;
import com.orisenc.security.OrisencSecurityProperties;
import com.orisenc.security.ServiceTokenProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads only user status from Common Platform; Workflow never reads its RBAC database. */
@Component
public class ActiveUserDirectory {
  private final RestClient client;

  public ActiveUserDirectory(OrisencAuthorizationProperties authorization,
      OrisencSecurityProperties security, RestClient.Builder builder) {
    if (!authorization.serviceIdentityConfigured()) {
      client = null;
      return;
    }
    var settings = ClientHttpRequestFactorySettings.defaults()
        .withConnectTimeout(authorization.requestTimeout())
        .withReadTimeout(authorization.requestTimeout());
    var requests = ClientHttpRequestFactoryBuilder.detect().build(settings);
    var tokens = new ServiceTokenProvider(authorization.clientId(), authorization.clientSecret(),
        authorization.scope(), security.tokenUri(), authorization.tokenRefreshSkew(),
        builder.clone().requestFactory(requests).build());
    client = builder.clone().baseUrl(authorization.baseUrl()).requestFactory(requests)
        .requestInterceptor((request, body, execution) -> {
          request.getHeaders().setBearerAuth(tokens.accessToken());
          var response = execution.execute(request, body);
          if (response.getStatusCode().value() == 401) tokens.invalidate();
          return response;
        }).build();
  }

  /**
   * Whether Common Platform knows this person and considers them active.
   *
   * <p>A 404 is an <em>answer</em>, not a failure: the platform looked and there is no such user, so
   * they are not an active one. Letting the 404 escape as an exception is what made adding a
   * mistyped delegate report "the delegate's active status could not be confirmed, try again when
   * Common Platform is available" - telling somebody to retry a thing that will never succeed, about
   * a service that was working perfectly. Everything else still throws, because a timeout or a 500
   * genuinely is "we could not find out", and a delegation must not be created on a guess.
   */
  public boolean isActive(String preferredUsername) {
    if (client == null) throw new IllegalStateException("Common Platform service identity is not configured");
    var answer = client.get().uri(uri -> uri.path("/api/access/users/by-username/{username}/status")
        .build(preferredUsername))
        .retrieve()
        // Only 404. A 401 or 403 means this service could not ask - suppressing those would turn
        // "Workflow cannot authenticate to Common Platform" into "that person is not active", which
        // is a worse answer than the one being fixed: it blames a user for an infrastructure fault.
        .onStatus(status -> status.value() == 404, (request, response) -> { })
        .body(UserStatus.class);
    return answer != null && answer.active();
  }

  private record UserStatus(String preferredUsername, String status, boolean active) {}
}
