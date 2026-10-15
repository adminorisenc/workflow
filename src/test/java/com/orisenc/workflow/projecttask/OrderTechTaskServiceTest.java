package com.orisenc.workflow.projecttask;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orisenc.workflow.delegation.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import java.time.*;
import java.util.*;

@DataJpaTest @TestPropertySource(properties="spring.jpa.hibernate.ddl-auto=create-drop")
class OrderTechTaskServiceTest {
  @Autowired EntityManager em;@Autowired ApplicationContext context;
  OrderTechTaskService service;ActiveUserDirectory users;OrderTechTeams teams;
  @BeforeEach void init(){
    users=mock(ActiveUserDirectory.class);when(users.isActive(anyString())).thenReturn(true);teams=mock(OrderTechTeams.class);
    var delegations=new DelegationService(em,new DelegationProperties(Duration.ofDays(90),null,"UTC",500),event->{},context.getBeanProvider(ActiveUserDirectory.class));
    var tasks=new ProjectTaskService(em,event->{},delegations);
    service=new OrderTechTaskService(em,tasks,users,teams,new ObjectMapper().findAndRegisterModules());
  }
  OrderTechTaskService.Command command(String person,Long team){return new OrderTechTaskService.Command("ops-tech-42","42","TNSO/FY27/0001","sales@example.com","Install equipment",LocalDate.of(2026,11,1),person,team);}
  @Test void repeatDeliveryReturnsOneTask(){var c=command("tech@example.com",null);var first=service.create(c,"service-object-id");em.flush();var retry=service.create(c,"service-object-id");assertThat(retry.taskId()).isEqualTo(first.taskId());assertThat(em.createQuery("select count(t) from OrderTechTaskEntity t",Long.class).getSingleResult()).isEqualTo(1);var task=em.find(ProjectTaskEntity.class,first.taskId());assertThat(task.getOwnerUserId()).isEqualTo("tech@example.com");assertThat(task.getVisibility()).isEqualTo(ProjectTaskVisibility.INDIVIDUAL);}
  @Test void changedCommandCannotCreateAnotherTask(){service.create(command("tech@example.com",null),"service");assertThatThrownBy(()->service.create(command("other@example.com",null),"service")).hasMessageContaining("different input");}
  @Test void teamUsesNamedActiveMembersWithoutBroadTeamAccess(){when(teams.members(7)).thenReturn(new OrderTechTeams.Members("Support",List.of("a@example.com","b@example.com","inactive@example.com")));when(users.isActive("inactive@example.com")).thenReturn(false);var result=service.create(command(null,7L),"service");var task=em.find(ProjectTaskEntity.class,result.taskId());assertThat(task.getOwnerUserId()).isNull();assertThat(task.isAssignee("a@example.com")).isTrue();assertThat(task.isAssignee("b@example.com")).isTrue();assertThat(task.isAssignee("inactive@example.com")).isFalse();assertThat(ProjectTaskVisibilityPolicy.mayView(task,"stranger",Set.of(ProjectTaskPermissions.VIEW,ProjectTaskPermissions.VIEW_TEAM))).isFalse();assertThat(ProjectTaskVisibilityPolicy.mayView(task,"a@example.com",Set.of(ProjectTaskPermissions.VIEW))).isTrue();}
  @Test void emptyOrInactiveTeamIsRejected(){when(teams.members(7)).thenReturn(new OrderTechTeams.Members("Empty",List.of()));assertThatThrownBy(()->service.create(command(null,7L),"service")).hasMessageContaining("no active users");}
  @Test void inactivePersonIsRejected(){when(users.isActive("tech@example.com")).thenReturn(false);assertThatThrownBy(()->service.create(command("tech@example.com",null),"service")).hasMessageContaining("no active users");}
  @Test void missingOrAmbiguousAssignmentIsRejected(){assertThatThrownBy(()->service.create(command(null,null),"service")).hasMessageContaining("person or team");assertThatThrownBy(()->service.create(command("tech@example.com",7L),"service")).hasMessageContaining("person or team");}
  @Test void membershipOutageDoesNotCreateTask(){when(teams.members(7)).thenThrow(new IllegalStateException("HR offline"));assertThatThrownBy(()->service.create(command(null,7L),"service")).hasMessageContaining("offline");assertThat(em.createQuery("select count(t) from OrderTechTaskEntity t",Long.class).getSingleResult()).isZero();}
}
