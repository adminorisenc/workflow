package com.orisenc.workflow.delegation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long a delegation may run, and how often the expiry pass looks.
 *
 * @param maxDuration the longest window a delegation may be created with. REQ-0027 asks for
 *     time-bound delegation, and a ten-year window is technically bounded and practically permanent;
 *     a cap is what makes "time-bound" mean something. Configurable because how long is reasonable
 *     is a business decision - until {@code REQ-0030}'s admin panel exists, a deployment override is
 *     the only way to change it
 * @param expiryCron when the expiry pass runs. Hourly by default: an expiry an hour late leaves
 *     somebody able to act on work that is no longer theirs, which is a smaller window than a daily
 *     pass and a far smaller one than nobody noticing at all
 * @param zone the timezone the cron is read in
 * @param batchSize the most delegations one pass will close, so a long-neglected table cannot turn a
 *     scheduled job into a run that outlives its own interval
 */
@ConfigurationProperties("orisenc.workflow.delegation")
public record DelegationProperties(
    Duration maxDuration,
    String expiryCron,
    String zone,
    int batchSize) {

  public DelegationProperties {
    maxDuration = maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()
        ? Duration.ofDays(90) : maxDuration;
    expiryCron = expiryCron == null || expiryCron.isBlank() ? "0 5 * * * *" : expiryCron.trim();
    zone = zone == null || zone.isBlank() ? "Asia/Kolkata" : zone.trim();
    batchSize = batchSize < 1 ? 500 : Math.min(batchSize, 5000);
  }
}
