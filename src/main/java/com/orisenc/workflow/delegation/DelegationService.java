package com.orisenc.workflow.delegation;

import static com.orisenc.workflow.delegation.DelegationDtos.*;

import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.notify.DelegationNotification;
import com.orisenc.workflow.projecttask.ProjectTaskType;
import com.orisenc.workflow.task.TaskType;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Delegation: who may act for whom, over what, and until when (REQ-0027).
 *
 * <p>This class does two jobs that look unrelated and are not. It manages delegation records - asked
 * for, approved, revoked, expired - and it answers the one question the rest of the service actually
 * cares about: <em>may this person act as that one on this piece of work right now?</em> Keeping
 * both here is deliberate. The moment the resolution rule lives somewhere other than the records it
 * reads, the two can disagree, and a delegation that has been revoked but still authorizes somebody
 * is the worst defect this feature can have.
 *
 * <h2>The four gates on creating one</h2>
 *
 * <ol>
 *   <li>You may delegate your own work. Delegating somebody else's needs
 *       {@code platform.delegation.manage}.
 *   <li>It must be time-bound, and bounded by {@code max-duration}. A window measured in years is
 *       technically bounded and practically permanent.
 *   <li>A privileged scope - anything including approvals - starts as {@code PENDING_APPROVAL} and
 *       authorizes nobody until a third person approves it.
 *   <li>It may not duplicate cover that already exists, so a double-submitted form does not leave
 *       two records and an ambiguous audit trail.
 * </ol>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p><b>Delegation does not chain.</b> If A delegates to B and B delegates to C, C cannot act as A.
 * Two hops make the effective actor something nobody can read off a single record, and they admit
 * cycles; one hop is what the workbook asks for and all that is auditable.
 *
 * <p><b>Delegation grants no permission.</b> See {@link DelegationEntity} - a delegate must already
 * hold the permission for the action, and this only widens whose records they may take it on.
 */
@Service
public class DelegationService {

  private static final int MAX_PAGE_SIZE = 200;

  private final EntityManager entityManager;
  private final DelegationProperties properties;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final java.util.function.Predicate<String> activeUsers;

  // Two constructors: this one for Spring, the package-private one below for tests that need a
  // fixed Clock. Without @Autowired Spring cannot choose between them - see SpringBeanConstructorTest.
  @Autowired
  public DelegationService(EntityManager entityManager, DelegationProperties properties,
      ApplicationEventPublisher events, ObjectProvider<ActiveUserDirectory> directory) {
    this(entityManager, properties, events, Clock.systemUTC(), username -> {
      var client = directory.getIfAvailable();
      if (client == null) throw new IllegalStateException("Common Platform user directory is unavailable");
      return client.isActive(username);
    });
  }

  DelegationService(EntityManager entityManager, DelegationProperties properties,
      ApplicationEventPublisher events, Clock clock) {
    this(entityManager, properties, events, clock, username -> true);
  }

  DelegationService(EntityManager entityManager, DelegationProperties properties,
      ApplicationEventPublisher events, Clock clock,
      java.util.function.Predicate<String> activeUsers) {
    this.entityManager = entityManager;
    this.properties = properties;
    this.events = events;
    this.clock = clock;
    this.activeUsers = activeUsers;
  }

  // ---------------------------------------------------------------- resolution

  /**
   * The delegation that lets {@code actor} act as {@code subject} on this work, if there is one.
   *
   * <p>Returned rather than a boolean because the caller needs the record: the history row it writes
   * must name the delegation that authorized it, or "acted on behalf of" is a claim with nothing
   * behind it.
   *
   * <p>Returns empty when actor and subject are the same person. That is not an error - it is the
   * ordinary case, and answering it here means callers can ask this question without first checking
   * whether they needed to.
   */
  @Transactional(readOnly = true)
  public Optional<DelegationEntity> authorising(String actor, String subject, DelegationTarget target) {
    if (actor == null || subject == null || actor.equalsIgnoreCase(subject.trim())) return Optional.empty();
    Instant now = clock.instant();
    return inForceFor(actor, now).stream()
        .filter(delegation -> delegation.getDelegatorUserId().equalsIgnoreCase(subject.trim()))
        .filter(delegation -> delegation.covers(target))
        .findFirst();
  }

  /** Every delegation currently handing work to {@code actor}, whatever it covers. */
  @Transactional(readOnly = true)
  public List<DelegationEntity> inbound(String actor) {
    return inForceFor(actor, clock.instant());
  }

  /**
   * Everybody currently standing in for {@code subject} on this work.
   *
   * <p>Used by the SLA pass: chasing somebody who is on leave, while the colleague covering for them
   * hears nothing, is an escalation that reaches the one person who cannot act on it.
   */
  @Transactional(readOnly = true)
  public List<String> delegatesOf(String subject, DelegationTarget target) {
    if (subject == null || subject.isBlank()) return List.of();
    Instant now = clock.instant();
    return entityManager.createQuery(
            "select d from DelegationEntity d where d.status = :active"
                + " and lower(d.delegatorUserId) = :subject"
                + " and d.validFrom <= :now and d.validUntil > :now", DelegationEntity.class)
        .setParameter("active", DelegationStatus.ACTIVE)
        .setParameter("subject", subject.trim().toLowerCase(Locale.ROOT))
        .setParameter("now", now)
        .getResultList().stream()
        .filter(delegation -> delegation.covers(target))
        .map(DelegationEntity::getDelegateUserId)
        .toList();
  }

  /**
   * The cover currently handing {@code actor} somebody else's work in one family.
   *
   * <p>Flattened to {@link DelegationCover} so the audience rules can use it without this package's
   * entities, and read once per request rather than per item: a queue of two hundred rows must not
   * become two hundred delegation lookups.
   */
  @Transactional(readOnly = true)
  public List<DelegationCover> coverFor(String actor, DelegationScope family) {
    return inForceFor(actor, clock.instant()).stream()
        .filter(delegation -> delegation.getScope().covers(family))
        .map(DelegationCover::of)
        .toList();
  }

  private List<DelegationEntity> inForceFor(String actor, Instant now) {
    if (actor == null || actor.isBlank()) return List.of();
    // The window is filtered in the database so a person with years of past cover does not load all
    // of it on every request; inForce is asserted again in Java because the two must not drift.
    return entityManager.createQuery(
            "select d from DelegationEntity d where d.status = :active"
                + " and lower(d.delegateUserId) = :actor"
                + " and d.validFrom <= :now and d.validUntil > :now"
                + " order by d.validUntil asc", DelegationEntity.class)
        .setParameter("active", DelegationStatus.ACTIVE)
        .setParameter("actor", actor.trim().toLowerCase(Locale.ROOT))
        .setParameter("now", now)
        .getResultList().stream()
        .filter(delegation -> delegation.inForce(now))
        .toList();
  }

  // ---------------------------------------------------------------- records

  /** Filter criteria. Every field is optional; nulls mean "do not narrow on this". */
  public record ListQuery(DelegationStatus status, String delegator, String delegate, Boolean inForceOnly,
      Integer page, Integer size) {}

  @Transactional(readOnly = true)
  public List<DelegationResponse> list(ListQuery query, String actor) {
    DelegationPermissions.require(DelegationPermissions.VIEW,
        "You do not have permission to view delegations.");

    var conditions = new ArrayList<String>();
    var parameters = new LinkedHashMap<String, Object>();

    if (!DelegationPermissions.holds(DelegationPermissions.MANAGE)) {
      // Yours, and the ones handing work to you. An approver additionally sees what is waiting on a
      // decision - without it the queue they are meant to work is invisible, and nothing else tells
      // them it exists: this service cannot enumerate who holds a permission, so nobody is notified.
      var mine = new StringBuilder("(lower(d.delegatorUserId) = :actor or lower(d.delegateUserId) = :actor");
      if (DelegationPermissions.holds(DelegationPermissions.APPROVE))
        mine.append(" or d.status = :pending");
      conditions.add(mine.append(")").toString());
      parameters.put("actor", actor.toLowerCase(Locale.ROOT));
      if (DelegationPermissions.holds(DelegationPermissions.APPROVE))
        parameters.put("pending", DelegationStatus.PENDING_APPROVAL);
    }
    if (query.status() != null) {
      conditions.add("d.status = :status");
      parameters.put("status", query.status());
    }
    if (notBlank(query.delegator())) {
      conditions.add("lower(d.delegatorUserId) = :delegator");
      parameters.put("delegator", resolveMe(query.delegator(), actor).toLowerCase(Locale.ROOT));
    }
    if (notBlank(query.delegate())) {
      conditions.add("lower(d.delegateUserId) = :delegate");
      parameters.put("delegate", resolveMe(query.delegate(), actor).toLowerCase(Locale.ROOT));
    }
    Instant now = clock.instant();
    if (Boolean.TRUE.equals(query.inForceOnly())) {
      conditions.add("d.status = :activeNow and d.validFrom <= :now and d.validUntil > :now");
      parameters.put("activeNow", DelegationStatus.ACTIVE);
      parameters.put("now", now);
    }

    String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    var jpql = entityManager.createQuery(
        "select d from DelegationEntity d" + where + " order by d.validUntil desc, d.id asc",
        DelegationEntity.class);
    parameters.forEach(jpql::setParameter);

    int size = Math.min(query.size() == null || query.size() < 1 ? 50 : query.size(), MAX_PAGE_SIZE);
    int page = query.page() == null || query.page() < 0 ? 0 : query.page();
    jpql.setFirstResult(page * size);
    jpql.setMaxResults(size);

    return jpql.getResultList().stream().map(delegation -> response(delegation, now)).toList();
  }

  @Transactional(readOnly = true)
  public DelegationResponse get(String id, String actor) {
    return response(readable(id, actor), clock.instant());
  }

  @Transactional
  public DelegationResponse create(CreateDelegationRequest request, String actor, String correlationId) {
    DelegationPermissions.require(DelegationPermissions.CREATE,
        "Your role does not permit arranging a delegation.");
    if (request == null) throw ApiException.badRequest("A delegation body is required");
    if (request.scope() == null) throw ApiException.badRequest("scope is required");
    require(request.delegateUserId(), "delegateUserId");
    require(request.reason(), "reason");
    if (request.reason().trim().length() > DelegationEntity.MAX_REASON)
      throw ApiException.badRequest("A reason cannot be longer than "
          + DelegationEntity.MAX_REASON + " characters.");

    String delegator = notBlank(request.delegatorUserId()) ? request.delegatorUserId().trim() : actor;
    if (!delegator.equalsIgnoreCase(actor))
      DelegationPermissions.require(DelegationPermissions.MANAGE,
          "Your role only permits delegating your own work.");

    String delegate = request.delegateUserId().trim();
    if (delegate.equalsIgnoreCase(delegator))
      throw ApiException.badRequest("A person cannot delegate their work to themselves.");
    try {
      if (!activeUsers.test(delegate))
        throw ApiException.badRequest("The delegate must be an active platform user.");
    } catch (ApiException refused) {
      throw refused;
    } catch (RuntimeException unavailable) {
      throw ApiException.serviceUnavailable(
          "The delegate's active status could not be confirmed. Try again when Common Platform is available.");
    }

    Instant now = clock.instant();
    Instant from = request.validFrom();
    if (from == null) throw ApiException.badRequest("validFrom is required");
    Instant until = request.validUntil();
    if (until == null) throw ApiException.badRequest("validUntil is required");
    if (!until.isAfter(from)) throw ApiException.badRequest("validUntil must be after validFrom");
    if (!until.isAfter(now)) throw ApiException.badRequest("validUntil must be in the future");
    if (Duration.between(from, until).compareTo(properties.maxDuration()) > 0)
      throw ApiException.badRequest("A delegation cannot run for longer than "
          + properties.maxDuration().toDays() + " days.");

    String taskType = validatedTaskType(request.scope(), request.taskType());
    String department = blankToNull(request.department());
    assertNoOverlap(delegator, delegate, request.scope(), from, until);

    var delegation = new DelegationEntity(newId(), delegator, delegate, request.scope(), taskType,
        department, from, until, request.reason().trim(), actor, now);
    delegation.addEvent("REQUESTED", actor, request.reason().trim(), now, correlationId);
    entityManager.persist(delegation);
    entityManager.flush();

    // Non-privileged delegations are live the moment they are written, so the delegate is told now.
    // A privileged one is told when somebody approves it, because until then it authorizes nothing
    // and a message saying otherwise would be wrong.
    if (delegation.getStatus() == DelegationStatus.ACTIVE) announceActive(delegation, actor);
    return response(delegation, now);
  }

  @Transactional
  public DelegationResponse approve(String id, DecisionRequest request, String actor,
      String correlationId) {
    DelegationPermissions.require(DelegationPermissions.APPROVE,
        "Your role does not permit deciding a delegation.");
    var delegation = readable(id, actor);
    assertVersion(delegation, request == null ? null : request.expectedVersion());
    Instant now = clock.instant();
    try {
      delegation.approve(actor, comment(request), now, correlationId);
    } catch (IllegalArgumentException refused) {
      // The entity owns the maker-checker rule; this only translates its refusal to the API contract.
      throw ApiException.badRequest(refused.getMessage());
    }
    entityManager.flush();
    announceActive(delegation, actor);
    return response(delegation, now);
  }

  @Transactional
  public DelegationResponse reject(String id, DecisionRequest request, String actor,
      String correlationId) {
    DelegationPermissions.require(DelegationPermissions.APPROVE,
        "Your role does not permit deciding a delegation.");
    var delegation = readable(id, actor);
    assertVersion(delegation, request == null ? null : request.expectedVersion());
    Instant now = clock.instant();
    try {
      delegation.reject(actor, comment(request), now, correlationId);
    } catch (IllegalArgumentException refused) {
      throw ApiException.badRequest(refused.getMessage());
    }
    entityManager.flush();
    announceEnded(delegation, actor, "refused");
    return response(delegation, now);
  }

  /**
   * Ends a delegation early.
   *
   * <p>Open to the delegator without any extra permission: taking back your own authority is not an
   * administrative act, and needing to find an administrator to do it is how a delegation outlives
   * the trust behind it. Anybody else needs {@code platform.delegation.manage}.
   */
  @Transactional
  public DelegationResponse revoke(String id, DecisionRequest request, String actor,
      String correlationId) {
    var delegation = readable(id, actor);
    if (!delegation.getDelegatorUserId().equalsIgnoreCase(actor))
      DelegationPermissions.require(DelegationPermissions.MANAGE,
          "Only the delegator or an administrator can revoke this delegation.");
    assertVersion(delegation, request == null ? null : request.expectedVersion());
    Instant now = clock.instant();
    try {
      delegation.revoke(actor, comment(request), now, correlationId);
    } catch (IllegalArgumentException refused) {
      throw ApiException.conflict(refused.getMessage());
    }
    entityManager.flush();
    announceEnded(delegation, actor, "revoked");
    return response(delegation, now);
  }

  // ---------------------------------------------------------------- internals

  private DelegationEntity readable(String id, String actor) {
    DelegationPermissions.require(DelegationPermissions.VIEW,
        "You do not have permission to view delegations.");
    var delegation = entityManager.find(DelegationEntity.class, id);
    if (delegation == null) throw ApiException.notFound("Delegation not found");
    if (DelegationPermissions.holds(DelegationPermissions.MANAGE)) return delegation;
    boolean party = delegation.getDelegatorUserId().equalsIgnoreCase(actor)
        || delegation.getDelegateUserId().equalsIgnoreCase(actor);
    boolean decidable = delegation.getStatus() == DelegationStatus.PENDING_APPROVAL
        && DelegationPermissions.holds(DelegationPermissions.APPROVE);
    // A delegation the caller may not see reports as missing rather than forbidden, the same rule
    // work items follow: a 403 would confirm that a delegation with this id exists.
    if (!party && !decidable) throw ApiException.notFound("Delegation not found");
    return delegation;
  }

  private void assertVersion(DelegationEntity delegation, Long expectedVersion) {
    if (expectedVersion == null) throw ApiException.badRequest("expectedVersion is required");
    if (delegation.getVersion() != expectedVersion)
      throw ApiException.conflict("Delegation was changed; reload and retry");
  }

  /**
   * Refuses a type name that cannot mean anything on this scope.
   *
   * <p>The two families have different type enums, so a delegation covering both cannot be narrowed
   * by a type at all - the name would exist in one family and silently match nothing in the other,
   * which reads on the record as a narrower delegation than it is.
   */
  private static String validatedTaskType(DelegationScope scope, String taskType) {
    if (!notBlank(taskType)) return null;
    String value = taskType.trim().toUpperCase(Locale.ROOT);
    return switch (scope) {
      case ALL -> throw ApiException.badRequest(
          "A delegation covering both approvals and work items cannot be narrowed to one task type;"
              + " choose a scope first.");
      case APPROVALS -> {
        if (!isEnum(TaskType.class, value))
          throw ApiException.badRequest(value + " is not an approval task type.");
        yield value;
      }
      case WORK_ITEMS -> {
        if (!isEnum(ProjectTaskType.class, value))
          throw ApiException.badRequest(value + " is not a work item type.");
        yield value;
      }
    };
  }

  private static <E extends Enum<E>> boolean isEnum(Class<E> type, String value) {
    for (E constant : type.getEnumConstants()) if (constant.name().equals(value)) return true;
    return false;
  }

  /**
   * Refuses cover that already exists.
   *
   * <p>Overlap is judged on the pair and the scope, not on the narrowing, and deliberately so: two
   * records that both authorize the same person over the same period are ambiguous in the audit
   * trail even when their departments differ, and the usual cause is somebody submitting the form
   * twice. Genuinely different cover is expressed by ending one and creating the other.
   */
  private void assertNoOverlap(String delegator, String delegate, DelegationScope scope,
      Instant from, Instant until) {
    boolean exists = !entityManager.createQuery(
            "select d from DelegationEntity d where d.status in :open"
                + " and lower(d.delegatorUserId) = :delegator and lower(d.delegateUserId) = :delegate"
                + " and d.scope = :scope and d.validFrom < :until and d.validUntil > :from",
            DelegationEntity.class)
        .setParameter("open", List.of(DelegationStatus.PENDING_APPROVAL, DelegationStatus.ACTIVE))
        .setParameter("delegator", delegator.toLowerCase(Locale.ROOT))
        .setParameter("delegate", delegate.toLowerCase(Locale.ROOT))
        .setParameter("scope", scope)
        .setParameter("from", from)
        .setParameter("until", until)
        .setMaxResults(1)
        .getResultList().isEmpty();
    if (exists)
      throw ApiException.conflict("A delegation of the same scope between these two people already "
          + "covers part of that period. End it first, or choose a different period.");
  }

  private void announceActive(DelegationEntity delegation, String actor) {
    events.publishEvent(new DelegationNotification(DelegationNotification.Kind.ACTIVE,
        delegation.getId(), eventRef(delegation), delegation.getDelegatorUserId(),
        delegation.getDelegateUserId(), actor, delegation.getScope().name(), delegation.getTaskType(),
        delegation.getDepartment(), delegation.getValidFrom(), delegation.getValidUntil(),
        delegation.getReason(), "active"));
  }

  private void announceEnded(DelegationEntity delegation, String actor, String outcome) {
    events.publishEvent(new DelegationNotification(DelegationNotification.Kind.ENDED,
        delegation.getId(), eventRef(delegation), delegation.getDelegatorUserId(),
        delegation.getDelegateUserId(), actor, delegation.getScope().name(), delegation.getTaskType(),
        delegation.getDepartment(), delegation.getValidFrom(), delegation.getValidUntil(),
        delegation.getReason(), outcome));
  }

  /** The event row that recorded this move, so the same delegation moving twice is two messages. */
  private static String eventRef(DelegationEntity delegation) {
    var history = delegation.getEvents();
    return history.isEmpty() ? delegation.getId()
        : delegation.getId() + "#" + history.getLast().getId();
  }

  private static String comment(DecisionRequest request) {
    return request == null ? null : request.comment();
  }

  private static String resolveMe(String value, String actor) {
    return "me".equalsIgnoreCase(value.trim()) ? actor : value.trim();
  }

  private static String newId() {
    return "DLG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  private static void require(String value, String field) {
    if (!notBlank(value)) throw ApiException.badRequest(field + " is required");
  }

  private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

  private static String blankToNull(String value) { return notBlank(value) ? value.trim() : null; }

  DelegationResponse response(DelegationEntity delegation, Instant now) {
    return new DelegationResponse(delegation.getId(), delegation.getVersion(),
        delegation.getDelegatorUserId(), delegation.getDelegateUserId(), delegation.getScope(),
        delegation.getTaskType(), delegation.getDepartment(), delegation.getValidFrom(),
        delegation.getValidUntil(), delegation.getStatus(), delegation.getScope().privileged(),
        delegation.inForce(now), delegation.getReason(), delegation.getCreatedBy(),
        delegation.getCreatedAt(), delegation.getDecidedBy(), delegation.getDecidedAt(),
        delegation.getDecisionReason(), delegation.getEndedAt(),
        delegation.getEvents().stream().map(event -> new DelegationEventResponse(event.getId(),
            event.getAction(), event.getActor(), event.getComment(), event.getOccurredAt(),
            event.getCorrelationId())).toList());
  }
}
