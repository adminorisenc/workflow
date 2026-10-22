package com.orisenc.workflow.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orisenc.workflow.delegation.DelegationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@DataJpaTest @TestPropertySource(properties="spring.jpa.hibernate.ddl-auto=create-drop")
class OpsMarginTaskServiceTest {
  @Autowired EntityManager em;
  OpsMarginTaskService service;TaskService tasks;
  @BeforeEach void init(){
    service=new OpsMarginTaskService(em,new ObjectMapper().findAndRegisterModules());
    tasks=new TaskService(em,event->{},mock(DelegationService.class));
    as("tl@example.com","platform.task.view","operations.margin.approve");
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  void as(String user,String... permissions){
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,
        java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
  }
  OpsMarginTaskService.Intake intake(long margin,String profit){
    return new OpsMarginTaskService.Intake("ops-margin-"+margin,2L,margin,3L,0L,"TNSO/FY27/0002","ops.user@example.com","INR",
        new BigDecimal("1000.00"),new BigDecimal("700.25"),new BigDecimal(profit),new BigDecimal("29.98"),profit.startsWith("-"),Instant.parse("2026-10-22T05:00:00Z"));
  }
  OpsMarginTaskService.State state(long version,String status,String actor,String reason){return new OpsMarginTaskService.State(5L,version,status,actor,Instant.now(),reason);}
  TaskEntity task(String id){em.flush();em.clear();return em.find(TaskEntity.class,id);}

  @Test void intakeCreatesOneUnassignedApprovalWithoutDueDateAndReplayReturnsIt(){
    var first=service.intake(intake(5,"299.75"),"ops-sp");var retry=service.intake(intake(5,"299.75"),"ops-sp");
    assertThat(retry.taskId()).isEqualTo(first.taskId());
    var t=task(first.taskId());
    assertThat(t.getType()).isEqualTo(TaskType.APPROVAL);assertThat(t.getAssignee()).isNull();assertThat(t.getDueAt()).isNull();
    assertThat(t.getReference()).isEqualTo("ops-margin-5");assertThat(t.getDepartment()).isEqualTo("Operations");assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(em.createQuery("select count(b) from OpsMarginTaskEntity b",Long.class).getSingleResult()).isEqualTo(1);
    assertThat(tasks.get(first.taskId()).overdue()).isFalse();
  }
  @Test void changedPayloadOrUnstableKeyIsRefused(){
    service.intake(intake(5,"299.75"),"ops-sp");
    assertThatThrownBy(()->service.intake(intake(5,"100.00"),"ops-sp")).hasMessageContaining("different input");
    var bad=new OpsMarginTaskService.Intake("other-key",2L,6L,3L,0L,"N","r@example.com","INR",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,false,Instant.now());
    assertThatThrownBy(()->service.intake(bad,"ops-sp")).hasMessageContaining("stable OPS margin identity");
  }
  @Test void opsDecisionClosesTaskAndDuplicateOrOlderReportsAreIgnored(){
    var id=service.intake(intake(5,"299.75"),"ops-sp").taskId();
    service.state(5,state(1,"APPROVED","tl@example.com",null),"ops-sp");
    assertThat(task(id).getStatus()).isEqualTo(TaskStatus.COMPLETED);
    service.state(5,state(1,"APPROVED","tl@example.com",null),"ops-sp");service.state(5,state(0,"REJECTED","x@example.com","old"),"ops-sp");
    var t=task(id);assertThat(t.getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThat(t.getHistory().stream().filter(h->h.getAction().startsWith("OPS_MARGIN")).count()).isEqualTo(1);
    assertThatThrownBy(()->service.state(5,state(1,"REJECTED","x@example.com","conflict"),"ops-sp")).hasMessageContaining("Conflicting");
  }
  @Test void rejectionAndInvalidationAfterApprovalCancelKeepingHistory(){
    var id=service.intake(intake(5,"299.75"),"ops-sp").taskId();
    service.state(5,state(1,"APPROVED","tl@example.com",null),"ops-sp");service.state(5,state(2,"INVALIDATED",null,"Vendor change: VENDOR_SELECTED"),"ops-sp");
    var t=task(id);assertThat(t.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(t.getHistory().stream().map(TaskHistoryEntity::getAction).toList()).contains("OPS_MARGIN_APPROVED","OPS_MARGIN_INVALIDATED");
    var other=service.intake(intake(6,"299.75"),"ops-sp").taskId();
    service.state(6,new OpsMarginTaskService.State(6L,1L,"REJECTED","tl@example.com",Instant.now(),"Too low"),"ops-sp");
    assertThat(task(other).getStatus()).isEqualTo(TaskStatus.REJECTED);
  }
  @Test void stateBeforeIntakeIsNotFound(){
    assertThatThrownBy(()->service.state(9,state(1,"APPROVED","tl@example.com",null),"ops-sp")).hasMessageContaining("not been received");
  }
  @Test void genericActionsCannotDecideAndNonApproversCannotSeeMarginTasks(){
    var id=service.intake(intake(5,"299.75"),"ops-sp").taskId();
    assertThatThrownBy(()->tasks.act(id,new TaskDtos.ActionRequest(TaskAction.APPROVE,null,0L),"tl@example.com","c")).hasMessageContaining("margin task");
    assertThat(service.binding(id).marginId()).isEqualTo(5L);
    as("user@example.com","platform.task.view","platform.task.approve");
    assertThat(tasks.list(null,null,null,null)).noneMatch(t->t.id().equals(id));
    assertThatThrownBy(()->tasks.get(id)).hasMessageContaining("not found");
    assertThatThrownBy(()->service.binding(id)).hasMessageContaining("not found");
  }
}
