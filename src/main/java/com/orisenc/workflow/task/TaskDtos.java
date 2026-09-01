package com.orisenc.workflow.task;

import java.time.Instant;
import java.util.List;

// ActionRequest deliberately has no client-supplied actor. The controller always derives the actor
// from the authenticated Entra principal, and TaskService validates request fields explicitly.
public final class TaskDtos {
  private TaskDtos() {}

  public record CreateTaskRequest(
      String title, String reference, String department, TaskType type, TaskPriority priority,
      String requester, String assignee, String summary, String requestValue, Instant dueAt) {}

  public record ActionRequest(TaskAction action, String comment, Long expectedVersion) {}

  public record HistoryResponse(Long id, String action, String actor, String comment, Instant occurredAt, String correlationId) {}

  public record TaskResponse(String id, long version, String title, String reference, String department,
      TaskType type, TaskPriority priority, TaskStatus status, String requester, String assignee,
      String summary, String requestValue, Instant createdAt, Instant dueAt, Instant completedAt,
      boolean overdue, List<HistoryResponse> history) {}
}
