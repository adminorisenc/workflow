package com.orisenc.workflow.delegation;

import java.time.Instant;
import java.util.List;

/**
 * Wire types for the delegation API.
 *
 * <p>No request carries an actor, for the same reason no other request in this service does: an
 * actor supplied by the client is an audit record that proves nothing. {@code delegatorUserId} is
 * the one identity a request may name, and only a holder of {@code platform.delegation.manage} may
 * name somebody other than themselves.
 */
public final class DelegationDtos {

  private DelegationDtos() {}

  /**
   * Asks for cover.
   *
   * @param delegatorUserId whose work is handed over. Null means the caller's own, which is the
   *     ordinary case; naming somebody else requires {@code platform.delegation.manage}
   * @param taskType a single type name from the scope's own type enum, or null for every type
   * @param department a department or relevant team to narrow to, or null for all
   */
  public record CreateDelegationRequest(
      String delegatorUserId, String delegateUserId, DelegationScope scope, String taskType,
      String department, Instant validFrom, Instant validUntil, String reason) {}

  public record DecisionRequest(String comment, Long expectedVersion) {}

  public record DelegationEventResponse(Long id, String action, String actor, String comment,
      Instant occurredAt, String correlationId) {}

  /**
   * One delegation.
   *
   * <p>{@code inForce} is resolved by the server rather than left to the client to work out from the
   * status and the two timestamps. A client that computed it would need this service's clock and its
   * inclusive/exclusive window rule, and would eventually show somebody a delegation as live at the
   * exact minute the API had started refusing it.
   */
  public record DelegationResponse(
      String id, long version, String delegatorUserId, String delegateUserId, DelegationScope scope,
      String taskType, String department, Instant validFrom, Instant validUntil,
      DelegationStatus status, boolean privileged, boolean inForce, String reason, String createdBy,
      Instant createdAt, String decidedBy, Instant decidedAt, String decisionReason, Instant endedAt,
      List<DelegationEventResponse> events) {}
}
