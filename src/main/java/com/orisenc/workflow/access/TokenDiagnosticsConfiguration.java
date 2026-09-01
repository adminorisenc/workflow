package com.orisenc.workflow.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orisenc.security.OrisencSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Logs why a token was refused, once, at the moment it is refused.
 *
 * <p>The shared starter answers every token failure with one constant error and publishes an audit
 * event whose reason is the fixed string {@code invalid_or_missing_token}. That is a deliberate and
 * correct choice for the HTTP response - an unauthenticated caller should not be told whether the
 * audience or the tenant was wrong - but it leaves the operator with nothing either, and the five
 * possible causes have five different fixes.
 *
 * <p>Wrapping the decoder rather than replacing it: the starter declares its {@code JwtDecoder}
 * without {@code @ConditionalOnMissingBean}, so a second one would be a bean conflict, and rebuilding
 * it here would duplicate the issuer and validator wiring that the starter is supposed to own. A
 * post-processor leaves that construction alone and only observes the outcome. The original exception
 * is rethrown untouched, so the response is byte-identical to before.
 *
 * <p>This belongs in the starter eventually. Until it is there, every service will hit the same wall
 * as it gets its own Entra registration.
 */
@Configuration
public class TokenDiagnosticsConfiguration {

  private static final Logger log = LoggerFactory.getLogger("com.orisenc.workflow.security.audit");

  @Bean
  static BeanPostProcessor jwtDecoderDiagnostics(ObjectProvider<OrisencSecurityProperties> properties) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof JwtDecoder decoder)) return bean;
        return (JwtDecoder) token -> {
          try {
            return decoder.decode(token);
          } catch (JwtException exception) {
            explain(properties.getIfAvailable(), token, exception);
            throw exception;
          }
        };
      }
    };
  }

  /**
   * Reads the claims out of the rejected token and names the check that would have failed.
   *
   * <p>Parses the payload segment directly instead of decoding again: the token has just been refused,
   * so anything that verifies it would refuse it a second time, and the claims are needed precisely
   * because it was refused. Nothing here trusts the content - it is only used to write a log line.
   */
  private static void explain(OrisencSecurityProperties properties, String token, JwtException exception) {
    if (properties == null) return;
    try {
      Map<String, Object> claims = claims(token);
      String reason = TokenRejectionDiagnosis.explain(claims, properties.tenantId(), properties.audiences(),
          properties.allowedClientApplicationIds());
      if (reason != null) {
        log.warn("Token refused: {}", reason);
        return;
      }
      // The claim checks all pass, so the failure was signature, issuer or expiry - handled before
      // them. Saying so is what stops someone hunting for an audience problem that is not there.
      log.warn("Token refused before the claim checks (signature, issuer or expiry): {}", exception.getMessage());
    } catch (RuntimeException unreadable) {
      log.warn("Token refused and could not be read as a JWT: {}", exception.getMessage());
    }
  }

  private static Map<String, Object> claims(String token) {
    String[] segments = token.split("\\.");
    if (segments.length < 2) throw new IllegalArgumentException("not a JWT");
    byte[] payload = Base64.getUrlDecoder().decode(segments[1]);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = new ObjectMapper().readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
      return parsed;
    } catch (Exception exception) {
      throw new IllegalArgumentException("unreadable payload", exception);
    }
  }
}
