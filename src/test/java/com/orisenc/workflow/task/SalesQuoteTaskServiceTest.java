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

@DataJpaTest @TestPropertySource(properties="spring.jpa.hibernate.ddl-auto=create-drop")
class SalesQuoteTaskServiceTest {
  @Autowired EntityManager em;
  SalesQuoteTaskService service;TaskService tasks;
  @BeforeEach void init(){
    service=new SalesQuoteTaskService(em,new ObjectMapper().findAndRegisterModules());
    tasks=new TaskService(em,event->{},mock(DelegationService.class));
    as("tl@example.com","platform.task.view","sales.quote.approve");
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  void as(String user,String... permissions){
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,
        java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
  }
  SalesQuoteTaskService.Intake intake(long quote,int round,String total){
    return new SalesQuoteTaskService.Intake("sales-quote-"+quote+"-"+round,quote,round,"QUO-2610-0007","Firewall renewal","Acme",
        "rep@example.com","rep@example.com","INR",new BigDecimal(total),new BigDecimal("800.00"),Instant.parse("2026-10-22T05:00:00Z"));
  }
  SalesQuoteTaskService.State state(int seq,String status,String actor,String reason){return new SalesQuoteTaskService.State(seq,status,actor,Instant.now(),reason);}
  TaskEntity task(String id){em.flush();em.clear();return em.find(TaskEntity.class,id);}

  @Test void intakeCreatesOneUnassignedSalesApprovalWithoutDueDateAndReplayReturnsIt(){
    var first=service.intake(intake(7,1,"1180.00"),"sales-sp");var retry=service.intake(intake(7,1,"1180.00"),"sales-sp");
    assertThat(retry.taskId()).isEqualTo(first.taskId());
    var t=task(first.taskId());
    assertThat(t.getType()).isEqualTo(TaskType.APPROVAL);assertThat(t.getAssignee()).isNull();assertThat(t.getDueAt()).isNull();
    assertThat(t.getReference()).isEqualTo("sales-quote-7-1");assertThat(t.getDepartment()).isEqualTo("Sales");assertThat(t.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(t.getSummary()).contains("Acme","1180.00 INR","round 1");
    assertThat(tasks.get(first.taskId()).overdue()).isFalse();
  }
  @Test void changedPayloadOrUnstableKeyIsRefusedAndANewRoundIsANewTask(){
    var first=service.intake(intake(7,1,"1180.00"),"sales-sp").taskId();
    assertThatThrownBy(()->service.intake(intake(7,1,"999.00"),"sales-sp")).hasMessageContaining("different input");
    var bad=new SalesQuoteTaskService.Intake("sales-quote-7-9",7L,2,"Q","s","a","o@example.com","o@example.com","INR",BigDecimal.ONE,null,Instant.now());
    assertThatThrownBy(()->service.intake(bad,"sales-sp")).hasMessageContaining("stable Sales quote approval identity");
    assertThat(service.intake(intake(7,2,"999.00"),"sales-sp").taskId()).isNotEqualTo(first);
  }
  @Test void decisionClosesTaskDuplicatesAreIgnoredAndCancellationAfterApprovalCancels(){
    var id=service.intake(intake(7,1,"1180.00"),"sales-sp").taskId();
    service.state("sales-quote-7-1",state(1,"APPROVED","tl@example.com",null),"sales-sp");
    service.state("sales-quote-7-1",state(1,"APPROVED","tl@example.com",null),"sales-sp");
    assertThat(task(id).getStatus()).isEqualTo(TaskStatus.COMPLETED);
    assertThatThrownBy(()->service.state("sales-quote-7-1",state(1,"REJECTED","x@example.com","no"),"sales-sp")).hasMessageContaining("Conflicting");
    service.state("sales-quote-7-1",state(2,"CANCELLED",null,"Lines changed after approval"),"sales-sp");
    var t=task(id);assertThat(t.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(t.getHistory().stream().map(TaskHistoryEntity::getAction).toList()).contains("SALES_QUOTE_APPROVED","SALES_QUOTE_CANCELLED");
    service.state("sales-quote-7-1",state(1,"APPROVED","tl@example.com",null),"sales-sp");
    assertThat(task(id).getStatus()).isEqualTo(TaskStatus.CANCELLED);
  }
  @Test void rejectionAndStateBeforeIntake(){
    var id=service.intake(intake(8,1,"10.00"),"sales-sp").taskId();
    service.state("sales-quote-8-1",state(1,"REJECTED","tl@example.com","Too cheap"),"sales-sp");
    assertThat(task(id).getStatus()).isEqualTo(TaskStatus.REJECTED);
    assertThatThrownBy(()->service.state("sales-quote-9-1",state(1,"APPROVED","tl@example.com",null),"sales-sp")).hasMessageContaining("not been received");
  }
  @Test void genericActionsCannotDecideAndNonApproversCannotSeeQuoteTasks(){
    var id=service.intake(intake(7,1,"1180.00"),"sales-sp").taskId();
    assertThatThrownBy(()->tasks.act(id,new TaskDtos.ActionRequest(TaskAction.APPROVE,null,0L),"tl@example.com","c")).hasMessageContaining("quote approval");
    assertThat(service.binding(id).quoteId()).isEqualTo(7L);
    as("ops@example.com","platform.task.view","platform.task.approve","operations.margin.approve");
    assertThat(tasks.list(null,null,null,null)).noneMatch(t->t.id().equals(id));
    assertThatThrownBy(()->tasks.get(id)).hasMessageContaining("not found");
    assertThatThrownBy(()->service.binding(id)).hasMessageContaining("not found");
  }
}
