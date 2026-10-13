package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.*;
import static com.orisenc.workflow.projecttask.ProjectTaskDtos.*;
import com.orisenc.workflow.delegation.DelegationService;
import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import(ProjectTaskService.class)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.generate_statistics=true"})
class ProjectTaskSubtaskServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant DUE = NOW.plusSeconds(86400);
  @Autowired EntityManager em;
  @Autowired ProjectTaskService service;
  @Autowired PlatformTransactionManager transactions;
  @org.springframework.test.context.bean.override.mockito.MockitoBean DelegationService delegations;
  @BeforeEach void setup() { when(delegations.coverFor(any(), any())).thenReturn(List.of()); signIn("owner"); }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  private void signIn(String actor) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
        List.of(new SimpleGrantedAuthority(ProjectTaskPermissions.VIEW), new SimpleGrantedAuthority(ProjectTaskPermissions.CREATE),
            new SimpleGrantedAuthority(ProjectTaskPermissions.EXECUTE))));
  }
  private CreateProjectTaskRequest request(List<SubtaskInput> children) {
    return new CreateProjectTaskRequest("Parent", "Work", ProjectTaskType.INTERNAL, TaskPriority.HIGH,
        ProjectTaskVisibility.ALL_TEAMS, "Operations", "owner", LinkedEntityType.CUSTOMER, "42", "CUST-42",
        null, DUE, null, 1L, 42L, null, null, null, null, children, true, null);
  }
  private SubtaskInput child(String title) { return new SubtaskInput(title, null, "owner", null, null); }
  @Test void createInheritsScopeLinksDatesAndRecordsHistory() {
    var parent = service.create(request(List.of(child("A"), child("B"))), "creator", "c");
    assertThat(parent.subtasksMandatory()).isTrue(); assertThat(parent.openSubtaskCount()).isEqualTo(2);
    for (var row : parent.children()) {
      var task = em.find(ProjectTaskEntity.class, row.id());
      assertThat(task.getRelevantTeam()).isEqualTo("Operations");
      assertThat(task.getVisibility()).isEqualTo(ProjectTaskVisibility.ALL_TEAMS);
      assertThat(task.getCustomerId()).isEqualTo(42L);
      assertThat(task.getLinkedEntityId()).isEqualTo("42");
      assertThat(task.getDueAt()).isEqualTo(DUE);
      assertThat(task.getPriority()).isEqualTo(TaskPriority.MEDIUM);
      assertThat(task.getStatus()).isEqualTo(ProjectTaskStatus.NOT_STARTED);
      assertThat(task.getHistory()).extracting(ProjectTaskHistoryEntity::getAction).containsExactly("CREATED");
    }
    assertThat(parent.history()).extracting(HistoryResponse::action).contains("SUBTASKS_ADDED");
    assertThat(service.get(parent.children().getFirst().id(), "stranger", java.util.Set.of(ProjectTaskPermissions.VIEW)).masterData()).isNull();
  }
  @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void invalidChildRollsBackTheWholeCreate() {
    var tx = new TransactionTemplate(transactions);
    long before = tx.execute(status -> em.createQuery("select count(t) from ProjectTaskEntity t", Long.class).getSingleResult());
    assertThatThrownBy(() -> service.create(request(List.of(child("A"), child(" "))), "owner", "c"));
    long after = tx.execute(status -> em.createQuery("select count(t) from ProjectTaskEntity t", Long.class).getSingleResult());
    assertThat(after).isEqualTo(before);
  }
  @Test void subtasksListInTheOrderTheyWereEnteredAndLaterAdditionsGoLast() {
    var parent = service.create(request(List.of(child("First"), child("Second"), child("Third"))), "owner", "c");
    assertThat(parent.children()).extracting(ProjectTaskSummary::title).containsExactly("First", "Second", "Third");
    var added = service.addSubtasks(parent.id(), new AddSubtasksRequest(List.of(child("Fourth")), null, parent.version()), "owner", "c");
    assertThat(added.children()).extracting(ProjectTaskSummary::title).containsExactly("First", "Second", "Third", "Fourth");
  }
  @Test void refusesChildDueAfterParent() {
    assertThatThrownBy(() -> service.create(request(List.of(new SubtaskInput("Late", null, null, DUE.plusSeconds(1), null))), "owner", "c"))
        .hasMessageContaining("after the parent");
  }
  @Test void refusesStartAfterChildDue() {
    assertThatThrownBy(() -> service.create(request(List.of(new SubtaskInput("Late", null, null, null, DUE.plusSeconds(1)))), "owner", "c"))
        .hasMessageContaining("planned start");
  }
  @Test void addRequiresParentOwnerDelegateOrManager() {
    var parent = service.create(request(null), "owner", "c"); signIn("stranger");
    assertThatThrownBy(() -> service.addSubtasks(parent.id(), new AddSubtasksRequest(List.of(child("A")), null, parent.version()), "stranger", "c"))
        .hasMessageContaining("parent owner");
  }
  @Test void namedAssigneeCannotAddSubtasks() {
    var parent = service.create(request(null), "owner", "c");
    em.find(ProjectTaskEntity.class, parent.id()).addAssignee("mate", "owner", NOW);
    signIn("mate");
    assertThatThrownBy(() -> service.addSubtasks(parent.id(), new AddSubtasksRequest(List.of(child("A")), null, parent.version()), "mate", "c"))
        .hasMessageContaining("parent owner");
  }
  @Test void closedAndArchivedParentsRefuseNewChildren() {
    var parent = service.create(request(null), "owner", "c");
    var entity = em.find(ProjectTaskEntity.class, parent.id()); entity.transition(ProjectTaskStatus.CANCELLED, "owner", "Stopped", NOW, "c");
    assertThatThrownBy(() -> service.addSubtasks(parent.id(), new AddSubtasksRequest(List.of(child("A")), null, entity.getVersion()), "owner", "c"))
        .hasMessageContaining("closed or archived");
    var second = service.create(request(null), "owner", "c"); em.find(ProjectTaskEntity.class, second.id()).archive(NOW);
    assertThatThrownBy(() -> service.addSubtasks(second.id(), new AddSubtasksRequest(List.of(child("A")), null, second.version()), "owner", "c"))
        .hasMessageContaining("closed or archived");
  }
  @Test void progressAndCompletionGateFollowCompletedCancelledAndArchivedChildren() {
    var parent = service.create(request(List.of(child("A"), child("B"), child("C"))), "owner", "c");
    var entity = em.find(ProjectTaskEntity.class, parent.id()); entity.beginSubtask(); entity.transition(ProjectTaskStatus.IN_PROGRESS, "owner", "Started", NOW, "c"); em.flush();
    assertThatThrownBy(() -> service.transition(parent.id(), new TransitionRequest(ProjectTaskStatus.COMPLETED, "Done", entity.getVersion()), "owner", "c"))
        .hasMessageContaining("3 subtasks are still open: WRK-");
    var a = parent.children().get(0); var b = parent.children().get(1); var c = parent.children().get(2);
    var active = service.transition(a.id(), new TransitionRequest(ProjectTaskStatus.IN_PROGRESS, "Started", a.version()), "owner", "c");
    service.transition(a.id(), new TransitionRequest(ProjectTaskStatus.COMPLETED, "Done", active.version()), "owner", "c");
    service.transition(b.id(), new TransitionRequest(ProjectTaskStatus.CANCELLED, "Not needed", b.version()), "owner", "c");
    em.find(ProjectTaskEntity.class, c.id()).archive(NOW); em.flush();
    var progress = service.get(parent.id(), "owner", ProjectTaskPermissions.granted());
    assertThat(progress.openSubtaskCount()).isZero(); assertThat(progress.completedSubtaskCount()).isEqualTo(1); assertThat(progress.childCount()).isEqualTo(2);
    assertThat(service.transition(parent.id(), new TransitionRequest(ProjectTaskStatus.COMPLETED, "Done", progress.version()), "owner", "c").status()).isEqualTo(ProjectTaskStatus.COMPLETED);
  }
  @Test void newOrderChainsAreMandatoryAndRefuseOpenStages() {
    var fixed = new ProjectTaskService(em, event -> {}, delegations, Clock.fixed(NOW, ZoneOffset.UTC));
    var parent = fixed.createOrderChain(new CreateOrderChainRequest("SO-1", "SO-1", "Customer", "Operations", null, null, "owner", DUE), "owner", "c");
    assertThat(parent.subtasksMandatory()).isTrue(); assertThat(parent.children()).hasSize(8);
    var entity = em.find(ProjectTaskEntity.class, parent.id()); entity.beginSubtask(); entity.transition(ProjectTaskStatus.IN_PROGRESS, "owner", "Started", NOW, "c"); em.flush();
    assertThatThrownBy(() -> fixed.transition(parent.id(), new TransitionRequest(ProjectTaskStatus.COMPLETED, "Done", entity.getVersion()), "owner", "c"))
        .hasMessageContaining("8 subtasks are still open");
  }
  @Test void listProgressUsesOneGroupedQueryRegardlessOfParentCount() {
    service.create(request(List.of(child("A"))), "owner", "c"); service.create(request(List.of(child("B"))), "owner", "c");
    var stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics(); stats.clear();
    var rows = service.list(new ProjectTaskService.ListQuery(null, null, null, null, null, null, null, null, null, null, null, 0, 50, true), "owner", ProjectTaskPermissions.granted());
    assertThat(rows).hasSize(2); assertThat(rows).allMatch(row -> row.openSubtaskCount() == 1);
    assertThat(java.util.Arrays.stream(stats.getQueries()).filter(q -> q.contains("group by t.parentTaskId"))).hasSize(1);
  }
  @Test void importsActiveChecklistTemplateAsSubtaskTitles() {
    var template = new ChecklistTemplateEntity("Template", null, List.of(new ChecklistTemplateItemInput("First", true)), "owner", NOW);
    em.persist(template); em.flush(); var parent = service.create(request(null), "owner", "c");
    var added = service.addSubtasks(parent.id(), new AddSubtasksRequest(null, template.getId(), parent.version()), "owner", "c");
    assertThat(added.children()).extracting(ProjectTaskSummary::title).containsExactly("First");
  }
}
