package com.orisenc.workflow.integration;

import com.orisenc.security.OrisencSecurityProperties;
import com.orisenc.security.ServiceTokenProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the bounded HTTP client Workflow uses to raise notifications.
 *
 * <p>Absent unless an operator enables it, so a deployment with no route to the Integration Service
 * has no client rather than a client that fails on every escalation. The SLA pass treats the absence
 * as "nobody can be told yet" and still records the escalation on the work item - see
 * {@code SlaEscalationService}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "orisenc.integration-client", name = "enabled", havingValue = "true")
public class IntegrationClientConfiguration {

  @Bean
  IntegrationServiceClient integrationServiceClient(IntegrationClientProperties properties,
      OrisencSecurityProperties security, RestClient.Builder restClientBuilder) {
    properties.validateConfigured();
    ClientHttpRequestFactory requests = requestFactory(properties);

    // This client talks only to Entra. It must never carry an API bearer token of its own.
    var tokenClient = restClientBuilder.clone().requestFactory(requests).build();
    var tokens = new ServiceTokenProvider(properties.clientId(), properties.clientSecret(),
        properties.scope(), security.tokenUri(), properties.tokenRefreshSkew(), tokenClient);

    var apiClient = restClientBuilder.clone()
        .baseUrl(properties.baseUrl())
        .requestFactory(requests)
        .requestInterceptor((request, body, execution) -> {
          request.getHeaders().setBearerAuth(tokens.accessToken());
          var response = execution.execute(request, body);
          if (response.getStatusCode().value() == 401) tokens.invalidate();
          return response;
        })
        .build();
    return new IntegrationServiceClient(apiClient);
  }

  private static ClientHttpRequestFactory requestFactory(IntegrationClientProperties properties) {
    var settings = ClientHttpRequestFactorySettings.defaults()
        .withConnectTimeout(properties.requestTimeout())
        .withReadTimeout(properties.requestTimeout());
    return ClientHttpRequestFactoryBuilder.detect().build(settings);
  }
}
