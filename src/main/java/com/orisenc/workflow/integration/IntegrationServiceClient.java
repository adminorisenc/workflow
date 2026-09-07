package com.orisenc.workflow.integration;

import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Raises notifications with the Integration Service, which is the only process that touches a third
 * party.
 *
 * <p>Narrower than the Accounts client of the same name: Workflow reads no projected documents, so
 * the only thing here is the raise.
 */
public class IntegrationServiceClient {

  /** The channel the Integration Service delivers on. Workflow only ever writes to people. */
  public enum Channel { EMAIL, TEAMS, IN_APP }

  /**
   * A notification to raise.
   *
   * <p>{@code eventType} and {@code eventRef} together with the recipient are what the Integration
   * Service is idempotent on. That is what lets the SLA pass run every quarter hour without a person
   * receiving the same escalation ninety-six times a day, and it is why the rung is part of the
   * event type rather than a field beside it - crossing the next rung is genuinely a new message.
   */
  public record NotificationRequest(String eventType, String eventRef, Channel channel, String recipient,
      String templateCode, Map<String, String> values, String subject, String body, Boolean critical,
      String correlationId) {}

  private final RestClient restClient;

  public IntegrationServiceClient(RestClient restClient) {
    this.restClient = restClient;
  }

  /**
   * Queues a notification for delivery.
   *
   * <p>Returns nothing on purpose. The caller has raised an intention, not sent a message: delivery
   * happens on the Integration Service's own schedule, and whether it arrived is a question for the
   * delivery log there - which is why every notification carries the event that caused it.
   */
  public void raiseNotification(NotificationRequest request) {
    restClient.post().uri("/api/notifications").body(request).retrieve().toBodilessEntity();
  }
}
