package com.orisenc.workflow.projecttask;

/**
 * Task audience, per TM-010. Narrowest reasonable scope is the default.
 *
 * <p>Policy note P-04 governs the boundary this enum does <em>not</em> cross: a broader audience
 * grants task metadata, status and comments - never customer identity or customer fields. Customer
 * access is a separate entitlement and is never inferred from task visibility.
 */
public enum ProjectTaskVisibility {
  /** Owner and creator only. */
  INDIVIDUAL,
  /** Plus the task's relevant team. */
  RELEVANT_TEAM,
  /** Any user holding task view permission. Metadata and status only. */
  ALL_TEAMS
}
