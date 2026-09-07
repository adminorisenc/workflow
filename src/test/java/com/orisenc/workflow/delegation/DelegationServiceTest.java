package com.orisenc.workflow.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orisenc.workflow.api.ApiException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

/**
 * The delegation records and the question everything else asks of them, against a real engine.
 *
 * <p>A slice rather than a mock test because the resolution rule is half Java and half a {@code
 * where} clause. {@link DelegationService#authorising} filters the window in the database and
 * asserts it again in Java on purpose - the two must not drift - and a test that mocked the query
 * would only ever exercise the half that was already obviously right.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DelegationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-10T09:00:00Z");
  private static final Instant UNTIL = NOW.plus(Duration.ofDays(7));

  private static final String DELEGATOR = "priya@orisenc.com";
  private static final String DELEGATE = "ravi@orisenc.com";
  private static final String APPROVER = "lead@orisenc.com";

  private static final Set<String> USER =
      Set.of(DelegationPermissions.VIEW, DelegationPermissions.CREATE);
  private static final Set<String> ADMIN = Set.of(DelegationPermissions.VIEW,
      DelegationPermissions.CREATE, DelegationPermissions.APPROVE, DelegationPermissions.MANAGE);

  @Autowired private EntityManager entityManager;

  private DelegationService service;
  private final List<Object> published = new java.util.ArrayList<>();

  @BeforeEach
  void setUp() {
    published.clear();
    ApplicationEventPublisher publisher = published::add;
    service = new DelegationService(entityManager,
        new DelegationProperties(Duration.ofDays(90), null, "UTC", 500), publisher,
        Clock.fixed(NOW, ZoneOffset.UTC));
    entityManager.createQuery("delete from DelegationEventEntity").executeUpdate();
    entityManager.createQuery("delete from DelegationEntity").executeUpdate();
    entityManager.flush();
  }

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  private void signedInAs(String user, Set<String> permissions) {
    var authorities = permissions.stream().map(SimpleGrantedAuthority::new).toList();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
  }

  private DelegationDtos.CreateDelegationRequest request(DelegationScope scope, String taskType,
      String department) {
    return new DelegationDtos.CreateDelegationRequest(null, DELEGATE, scope, taskType, department,
        NOW, UNTIL, "Annual leave");
  }

  // ---------------------------------------------------------------- creating

  @Test
  void arrangingCoverForYourOwnWorkNeedsNothingButTheCreatePermission() {
    signedInAs(DELEGATOR, USER);

    var created = service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");

    assertThat(created.status()).isEqualTo(DelegationStatus.ACTIVE);
    assertThat(created.inForce()).isTrue();
    assertThat(created.privileged()).isFalse();
    assertThat(created.delegatorUserId()).isEqualTo(DELEGATOR);
  }

  @Test
  void arrangingCoverForSomebodyElseIsAnAdministrativeAct() {
    // Otherwise anybody could hand another person's queue to a third, which is not delegation.
    signedInAs("meddler@orisenc.com", USER);
    var forSomeoneElse = new DelegationDtos.CreateDelegationRequest(DELEGATOR, DELEGATE,
        DelegationScope.WORK_ITEMS, null, null, NOW, UNTIL, "Annual leave");

    assertThatThrownBy(() -> service.create(forSomeoneElse, "meddler@orisenc.com", "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("only permits delegating your own work");
  }

  @Test
  void handingOverApprovalsWaitsOnADecisionAndTellsNobodyYet() {
    signedInAs(DELEGATOR, USER);

    var created = service.create(request(DelegationScope.APPROVALS, null, null), DELEGATOR, "c");

    assertThat(created.status()).isEqualTo(DelegationStatus.PENDING_APPROVAL);
    assertThat(created.inForce()).isFalse();
    // No notification: telling the delegate they hold authority they do not yet hold is worse than
    // silence, because they will act on it.
    assertThat(published).isEmpty();
  }

  @Test
  void aNonPrivilegedDelegationTellsTheDelegateStraightAway() {
    signedInAs(DELEGATOR, USER);

    service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");

    assertThat(published).hasSize(1);
  }

  @Test
  void aDelegationMustBeTimeBoundAndBoundedByTheConfiguredMaximum() {
    signedInAs(DELEGATOR, USER);
    var tooLong = new DelegationDtos.CreateDelegationRequest(null, DELEGATE,
        DelegationScope.WORK_ITEMS, null, null, NOW, NOW.plus(Duration.ofDays(400)), "Sabbatical");

    assertThatThrownBy(() -> service.create(tooLong, DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("cannot run for longer than 90 days");
  }

  @Test
  void bothEndsOfTheWindowAreRequired() {
    signedInAs(DELEGATOR, USER);
    var noStart = new DelegationDtos.CreateDelegationRequest(null, DELEGATE,
        DelegationScope.WORK_ITEMS, null, null, null, UNTIL, "Annual leave");
    var noEnd = new DelegationDtos.CreateDelegationRequest(null, DELEGATE,
        DelegationScope.WORK_ITEMS, null, null, NOW, null, "Annual leave");

    assertThatThrownBy(() -> service.create(noStart, DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("validFrom is required");
    assertThatThrownBy(() -> service.create(noEnd, DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("validUntil is required");
  }

  @Test
  void aWindowThatHasAlreadyClosedIsRefused() {
    signedInAs(DELEGATOR, USER);
    var past = new DelegationDtos.CreateDelegationRequest(null, DELEGATE,
        DelegationScope.WORK_ITEMS, null, null, NOW.minus(Duration.ofDays(10)),
        NOW.minus(Duration.ofDays(3)), "Last month");

    assertThatThrownBy(() -> service.create(past, DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("must be in the future");
  }

  @Test
  void nobodyDelegatesToThemselves() {
    signedInAs(DELEGATOR, USER);
    var toSelf = new DelegationDtos.CreateDelegationRequest(null, DELEGATOR,
        DelegationScope.WORK_ITEMS, null, null, NOW, UNTIL, "Annual leave");

    assertThatThrownBy(() -> service.create(toSelf, DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("cannot delegate their work to themselves");
  }

  @Test
  void anInactiveDelegateIsRefused() {
    signedInAs(DELEGATOR, USER);
    service = new DelegationService(entityManager,
        new DelegationProperties(Duration.ofDays(90), null, "UTC", 500), published::add,
        Clock.fixed(NOW, ZoneOffset.UTC), username -> false);

    assertThatThrownBy(() -> service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("active platform user");
  }

  @Test
  void aTaskTypeThatCannotMeanAnythingOnThisScopeIsRefused() {
    signedInAs(DELEGATOR, USER);

    // A work item type on an approvals delegation.
    assertThatThrownBy(() ->
        service.create(request(DelegationScope.APPROVALS, "FIELD_VISIT", null), DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("not an approval task type");

    // And no type at all on a delegation spanning both, because the name exists in only one family
    // and would read on the record as a narrower delegation than it is.
    assertThatThrownBy(() ->
        service.create(request(DelegationScope.ALL, "APPROVAL", null), DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("choose a scope first");
  }

  @Test
  void theSameCoverCannotBeArrangedTwice() {
    // The usual cause is a double-submitted form, and two records authorizing the same person over
    // the same period are ambiguous in the audit trail even when nothing else differs.
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");

    assertThatThrownBy(() ->
        service.create(request(DelegationScope.WORK_ITEMS, null, "Operations"), DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("already covers part of that period");
  }

  @Test
  void coverForADifferentFamilyIsNotADuplicate() {
    signedInAs(DELEGATOR, ADMIN);
    service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");

    var approvals = service.create(request(DelegationScope.APPROVALS, null, null), DELEGATOR, "c");

    assertThat(approvals.status()).isEqualTo(DelegationStatus.PENDING_APPROVAL);
  }

  // ---------------------------------------------------------------- deciding

  @Test
  void approvingItMakesItLiveAndTellsTheDelegate() {
    signedInAs(DELEGATOR, USER);
    var created = service.create(request(DelegationScope.APPROVALS, null, null), DELEGATOR, "c");
    published.clear();

    signedInAs(APPROVER, ADMIN);
    var approved = service.approve(created.id(),
        new DelegationDtos.DecisionRequest("Cover agreed.", created.version()), APPROVER, "c");

    assertThat(approved.status()).isEqualTo(DelegationStatus.ACTIVE);
    assertThat(approved.inForce()).isTrue();
    assertThat(approved.decidedBy()).isEqualTo(APPROVER);
    assertThat(published).hasSize(1);
  }

  @Test
  void theDelegatorCannotApproveTheirOwnRequestEvenAsAnAdministrator() {
    // Maker-checker survives the permission. An administrator delegating their own approvals still
    // needs a second person, which is the point.
    signedInAs(DELEGATOR, ADMIN);
    var created = service.create(request(DelegationScope.APPROVALS, null, null), DELEGATOR, "c");

    assertThatThrownBy(() -> service.approve(created.id(),
        new DelegationDtos.DecisionRequest("fine", created.version()), DELEGATOR, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("your own delegation");
  }

  @Test
  void aStaleVersionIsRefusedRatherThanSilentlyDeciding() {
    signedInAs(DELEGATOR, USER);
    var created = service.create(request(DelegationScope.APPROVALS, null, null), DELEGATOR, "c");

    signedInAs(APPROVER, ADMIN);
    assertThatThrownBy(() -> service.approve(created.id(),
        new DelegationDtos.DecisionRequest("fine", created.version() + 5), APPROVER, "c"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("reload and retry");
  }

  @Test
  void theDelegatorCanTakeTheirOwnAuthorityBackWithoutAnAdministrator() {
    // Needing to find an administrator is how a delegation outlives the trust behind it.
    signedInAs(DELEGATOR, USER);
    var created = service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");

    var revoked = service.revoke(created.id(),
        new DelegationDtos.DecisionRequest("Back early.", created.version()), DELEGATOR, "c");

    assertThat(revoked.status()).isEqualTo(DelegationStatus.REVOKED);
    assertThat(revoked.inForce()).isFalse();
  }

  // ---------------------------------------------------------------- resolution

  @Test
  void anInForceDelegationAuthorisesTheDelegateOnCoveredWork() {
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.WORK_ITEMS, null, "Operations"), DELEGATOR, "c");

    assertThat(service.authorising(DELEGATE, DELEGATOR,
        DelegationTarget.workItem("DELIVERABLE", "Operations"))).isPresent();
  }

  @Test
  void itAuthorisesNothingOutsideWhatItCovers() {
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.WORK_ITEMS, null, "Operations"), DELEGATOR, "c");

    // Wrong team.
    assertThat(service.authorising(DELEGATE, DELEGATOR,
        DelegationTarget.workItem("DELIVERABLE", "Tech"))).isEmpty();
    // Wrong family - covering deliveries must not hand over approvals.
    assertThat(service.authorising(DELEGATE, DELEGATOR,
        DelegationTarget.approval("APPROVAL", "Operations"))).isEmpty();
    // Wrong delegate.
    assertThat(service.authorising("stranger@orisenc.com", DELEGATOR,
        DelegationTarget.workItem("DELIVERABLE", "Operations"))).isEmpty();
  }

  @Test
  void aPendingDelegationAuthorisesNobody() {
    // The whole content of the privileged gate: created, visible, and powerless until approved.
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.APPROVALS, null, null), DELEGATOR, "c");

    assertThat(service.authorising(DELEGATE, DELEGATOR,
        DelegationTarget.approval("APPROVAL", "Finance"))).isEmpty();
  }

  @Test
  void aRevokedDelegationStopsAuthorisingImmediately() {
    // The worst defect this feature could have, so it is asserted rather than assumed.
    signedInAs(DELEGATOR, USER);
    var created = service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");
    service.revoke(created.id(),
        new DelegationDtos.DecisionRequest("Back early.", created.version()), DELEGATOR, "c");

    assertThat(service.authorising(DELEGATE, DELEGATOR,
        DelegationTarget.workItem("DELIVERABLE", "Operations"))).isEmpty();
  }

  @Test
  void delegationDoesNotChain() {
    // A delegates to B and B delegates to C. C cannot act as A: two hops make the effective actor
    // something nobody can read off a single record, and they admit cycles.
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");
    signedInAs(DELEGATE, USER);
    service.create(new DelegationDtos.CreateDelegationRequest(null, "third@orisenc.com",
        DelegationScope.WORK_ITEMS, null, null, NOW, UNTIL, "Also away"), DELEGATE, "c");

    assertThat(service.authorising("third@orisenc.com", DELEGATE,
        DelegationTarget.workItem("DELIVERABLE", "Operations"))).isPresent();
    assertThat(service.authorising("third@orisenc.com", DELEGATOR,
        DelegationTarget.workItem("DELIVERABLE", "Operations"))).isEmpty();
  }

  @Test
  void theSlaPassCanAskWhoIsStandingInForSomebody() {
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");

    assertThat(service.delegatesOf(DELEGATOR, DelegationTarget.workItem("DELIVERABLE", "Operations")))
        .containsExactly(DELEGATE);
    assertThat(service.delegatesOf(DELEGATOR, DelegationTarget.approval("APPROVAL", "Finance")))
        .isEmpty();
  }

  @Test
  void coverIsReadOncePerFamilyAndCarriesItsNarrowing() {
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.WORK_ITEMS, "DELIVERABLE", "Operations"), DELEGATOR, "c");

    var cover = service.coverFor(DELEGATE, DelegationScope.WORK_ITEMS);

    assertThat(cover).singleElement().satisfies(entry -> {
      assertThat(entry.delegator()).isEqualTo(DELEGATOR);
      assertThat(entry.covers("DELIVERABLE", "Operations")).isTrue();
      assertThat(entry.covers("FIELD_VISIT", "Operations")).isFalse();
    });
    assertThat(service.coverFor(DELEGATE, DelegationScope.APPROVALS)).isEmpty();
  }

  // ---------------------------------------------------------------- audience

  @Test
  void youSeeYourOwnDelegationsAndTheOnesHandingWorkToYou() {
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");

    signedInAs(DELEGATE, USER);
    assertThat(service.list(new DelegationService.ListQuery(null, null, null, null, null, null),
        DELEGATE)).hasSize(1);

    signedInAs("stranger@orisenc.com", USER);
    assertThat(service.list(new DelegationService.ListQuery(null, null, null, null, null, null),
        "stranger@orisenc.com")).isEmpty();
  }

  @Test
  void anApproverSeesTheQueueWaitingOnThem() {
    // Nothing else tells them it exists: this service cannot enumerate who holds a permission, so
    // no notification finds an approver. The screen is the queue.
    signedInAs(DELEGATOR, USER);
    service.create(request(DelegationScope.APPROVALS, null, null), DELEGATOR, "c");

    signedInAs(APPROVER, Set.of(DelegationPermissions.VIEW, DelegationPermissions.APPROVE));
    assertThat(service.list(new DelegationService.ListQuery(null, null, null, null, null, null),
        APPROVER)).hasSize(1);
  }

  @Test
  void aDelegationYouMayNotSeeReportsAsMissingRatherThanForbidden() {
    // A 403 would confirm that a delegation with this id exists, which the caller is not entitled to.
    signedInAs(DELEGATOR, USER);
    var created = service.create(request(DelegationScope.WORK_ITEMS, null, null), DELEGATOR, "c");

    signedInAs("stranger@orisenc.com", USER);
    assertThatThrownBy(() -> service.get(created.id(), "stranger@orisenc.com"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("not found");
  }
}
