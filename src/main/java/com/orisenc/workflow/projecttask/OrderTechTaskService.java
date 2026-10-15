package com.orisenc.workflow.projecttask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orisenc.workflow.api.ApiException;
import com.orisenc.workflow.delegation.ActiveUserDirectory;
import com.orisenc.workflow.task.TaskPriority;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class OrderTechTaskService {
  private final EntityManager em;private final ProjectTaskService tasks;private final ActiveUserDirectory users;
  private final OrderTechTeams teams;private final ObjectMapper json;
  public OrderTechTaskService(EntityManager em,ProjectTaskService tasks,ActiveUserDirectory users,OrderTechTeams teams,ObjectMapper json){this.em=em;this.tasks=tasks;this.users=users;this.teams=teams;this.json=json;}
  public record Command(String requestKey,String orderId,String orderNumber,String requestedBy,
      String description,LocalDate dueDate,String person,Long teamId) {}
  public record Result(String taskId) {}
  @Transactional
  public Result create(Command c,String serviceActor) {
    if(c==null||c.orderId()==null||!c.orderId().matches("[1-9][0-9]{0,18}")||!Objects.equals(c.requestKey(),"ops-tech-"+c.orderId()))throw ApiException.badRequest("A stable Operations order identity is required.");
    required(c.orderNumber(),60,"SO number");required(c.requestedBy(),200,"Requester");required(c.description(),4000,"Tech work description");
    if(c.dueDate()==null||(c.person()==null)==(c.teamId()==null))throw ApiException.badRequest("Choose a person or team and a required date.");
    String hash;
    try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(c)));}catch(Exception e){throw new IllegalStateException(e);}
    var previous=em.find(OrderTechTaskEntity.class,c.requestKey());
    if(previous!=null){if(!previous.requestHash.equals(hash))throw ApiException.conflict("This order task request was used for different input.");return new Result(previous.taskId);}
    var recipients=new LinkedHashSet<String>();String teamName="Tech";
    if(c.person()!=null){required(c.person(),200,"Assignee");recipients.add(c.person().toLowerCase(Locale.ROOT));}
    else {var team=teams.members(c.teamId());teamName=team.name();recipients.addAll(team.usernames());}
    // Explicit named membership avoids RELEVANT_TEAM's currently broader permission boundary.
    recipients.removeIf(name->!users.isActive(name));
    if(recipients.isEmpty())throw ApiException.badRequest("The selected assignment has no active users.");
    if(!users.isActive(c.requestedBy()))throw ApiException.badRequest("The requester is not an active user.");
    var delivery=new OrderTechTaskEntity();delivery.requestKey=c.requestKey();delivery.orderId=c.orderId();delivery.requestHash=hash;
    delivery.requestedBy=c.requestedBy();delivery.serviceActor=serviceActor;delivery.createdAt=Instant.now();em.persist(delivery);em.flush();
    String relevantTeam=c.teamId()==null?"Tech":"HR team "+c.teamId();
    var created=tasks.create(new ProjectTaskDtos.CreateProjectTaskRequest(
        "Tech work · "+c.orderNumber(),c.description(),ProjectTaskType.DELIVERABLE,TaskPriority.MEDIUM,
        ProjectTaskVisibility.INDIVIDUAL,relevantTeam,c.person()==null?null:recipients.iterator().next(),
        LinkedEntityType.SALES_ORDER,c.orderId(),c.orderNumber(),null,
        c.dueDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).minusSeconds(1).toInstant(),List.of(),
        null,null,null,"Requested by "+c.requestedBy()+" · "+teamName,null,null),serviceActor,c.requestKey());
    var task=em.find(ProjectTaskEntity.class,created.id());
    if(c.teamId()!=null)for(String user:recipients)task.addAssignee(user,serviceActor,Instant.now());
    task.addFollower(c.requestedBy(),serviceActor,Instant.now());
    task.addHistory("ORDER_TECH_REQUESTED",serviceActor,"Requested by "+c.requestedBy()+
        (c.teamId()==null?"":"; assigned to active members of HR team "+c.teamId()),Instant.now(),c.requestKey());
    delivery.taskId=created.id();return new Result(delivery.taskId);
  }
  static void required(String value,int max,String name){if(value==null||value.isBlank()||value.length()>max)throw ApiException.badRequest(name+" is required and must be at most "+max+" characters.");}
}
