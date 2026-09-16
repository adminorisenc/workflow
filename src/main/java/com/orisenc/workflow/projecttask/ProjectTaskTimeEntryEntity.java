package com.orisenc.workflow.projecttask;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One stretch of effort somebody spent on a work item (TM-A05, {@code task_time_entries}).
 *
 * <p>The architecture workbook designs this table with a {@code status} column for a submit/approve
 * cycle. It is deliberately absent: the agreed scope records effort and does not approve it, so a
 * status column would have exactly one value forever and would imply a gate nothing enforces. It can
 * be added the day approval is wanted - that is an added column, not a reshaping.
 *
 * <p>{@code note} is the person's own account of what they did, which is the half of TM-A02 that
 * belongs to the worker rather than to the task. The task's own account lives in
 * {@link ProjectTaskEntity#getSummary()}.
 *
 * <p>{@code username} rather than an Entra object id, matching {@code ownerUserId}, {@code createdBy}
 * and every history actor on the item - one kind of identity comparison across the whole feature.
 *
 * <p>The id is a sequence rather than a UUID for the same reason {@link ProjectTaskHistoryEntity}'s
 * is: two entries logged in the same second must still read back in the order they were written, and
 * a random key orders them arbitrarily.
 */
@Entity
@Table(name = "project_task_time_entry", indexes = {
    @Index(name = "idx_project_task_time_task", columnList = "task_id,work_date"),
    @Index(name = "idx_project_task_time_user", columnList = "username,work_date")
})
public class ProjectTaskTimeEntryEntity {

  /** A day's work. Anything longer is a typo, not a long day. */
  public static final int MAX_MINUTES = 24 * 60;
  public static final int MAX_NOTE = 2000;

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "task_id", nullable = false) private ProjectTaskEntity task;

  @Column(name = "username", nullable = false, length = 120) private String username;
  @Column(name = "work_date", nullable = false) private LocalDate workDate;
  @Column(name = "duration_minutes", nullable = false) private int durationMinutes;
  @Column(name = "note", length = MAX_NOTE) private String note;

  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "created_by", nullable = false, length = 120) private String createdBy;
  /** Set when the entry has been corrected since, so the roll-up can say so without a history join. */
  @Column(name = "updated_at") private Instant updatedAt;

  protected ProjectTaskTimeEntryEntity() {}

  ProjectTaskTimeEntryEntity(ProjectTaskEntity task, String username, LocalDate workDate,
      int durationMinutes, String note, String createdBy, Instant now) {
    this.task = task;
    this.username = requiredIdentity(username, "The person who did the work");
    this.workDate = requiredDate(workDate);
    this.durationMinutes = requiredDuration(durationMinutes);
    this.note = trimmedNote(note);
    this.createdBy = requiredIdentity(createdBy, "Created by");
    this.createdAt = now;
  }

  /**
   * Corrects an entry in place.
   *
   * <p>Only the person who logged it may reach this - the service enforces that - and the correction
   * writes its own history row, so a changed duration is visible in the activity feed rather than
   * silently replacing what was there.
   */
  void correct(LocalDate workDate, int durationMinutes, String note, Instant now) {
    this.workDate = requiredDate(workDate);
    this.durationMinutes = requiredDuration(durationMinutes);
    this.note = trimmedNote(note);
    this.updatedAt = now;
  }

  /**
   * Whether this entry is this person's to change.
   *
   * <p>Either the person the hours are attributed to or the person who recorded them, which are the
   * same person in every case except a manager logging effort on somebody's behalf. Both should be
   * able to fix a typo in it; nobody else should, because a correction is a restatement of what
   * somebody did and it is worth more when only they can make it.
   *
   * <p>Case-insensitive, as identity comparison is everywhere in this feature.
   */
  public boolean belongsTo(String candidate) {
    if (candidate == null || candidate.isBlank()) return false;
    String trimmed = candidate.trim();
    return username.equalsIgnoreCase(trimmed) || createdBy.equalsIgnoreCase(trimmed);
  }

  private static LocalDate requiredDate(LocalDate value) {
    if (value == null) throw new IllegalArgumentException("A work date is required.");
    return value;
  }

  private static int requiredDuration(int minutes) {
    if (minutes <= 0) throw new IllegalArgumentException("Time spent must be more than zero minutes.");
    if (minutes > MAX_MINUTES)
      throw new IllegalArgumentException("A single entry cannot be longer than 24 hours.");
    return minutes;
  }

  private static String trimmedNote(String value) {
    if (value == null || value.isBlank()) return null;
    String trimmed = value.trim();
    if (trimmed.length() > MAX_NOTE)
      throw new IllegalArgumentException("A note cannot be longer than " + MAX_NOTE + " characters.");
    return trimmed;
  }

  private static String requiredIdentity(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
    String trimmed = value.trim();
    if (trimmed.length() > 120)
      throw new IllegalArgumentException(field + " must be 120 characters or fewer.");
    return trimmed;
  }

  public Long getId() { return id; }
  public String getTaskId() { return task.getId(); }
  public String getUsername() { return username; }
  public LocalDate getWorkDate() { return workDate; }
  public int getDurationMinutes() { return durationMinutes; }
  public String getNote() { return note; }
  public Instant getCreatedAt() { return createdAt; }
  public String getCreatedBy() { return createdBy; }
  public Instant getUpdatedAt() { return updatedAt; }
}
