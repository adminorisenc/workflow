package com.orisenc.workflow.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orisenc.workflow.api.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * OPS margin approval tasks (Workflow/docs/OPS_MARGIN_APPROVAL_TASK_CONTRACT.md, owner decisions 22 Oct 2026).
 * OPS submits each evaluation once (intake) and reports every decision or invalidation (state). The task is
 * open to every holder of operations.margin.approve, has no due date, and is never decided here: the
 * browser sends the TL's decision to OPS with the TL's own token, and OPS's state message closes the task.
 */
// @Service // Margin approval disabled by owner decision, 22 Oct 2026.
public class OpsMarginTaskService {
  static final String PREFIX = "ops-margin-";
  static final String APPROVE_PERMISSION = "operations.margin.approve";
  private final EntityManager em;private final ObjectMapper json;
  public OpsMarginTaskService(EntityManager em,ObjectMapper json){this.em=em;this.json=json;}

  public record Intake(String requestKey,Long orderId,Long marginId,Long revisionId,Long marginVersion,String orderNumber,
      String requestedBy,String currency,BigDecimal sellingTotal,BigDecimal selectedCostTotal,BigDecimal grossProfit,
      BigDecimal marginPct,boolean negative,Instant submittedAt) {}
  public record State(Long marginId,Long opsVersion,String status,String actor,Instant at,String reason) {}
  public record Result(String taskId,String sourceStatus) {}
  public record Binding(String taskId,Long orderId,Long marginId,Long revisionId,Long marginVersion,String orderNumber,String sourceStatus) {}

  static boolean isMarginTask(TaskEntity task) {return task.getReference()!=null&&task.getReference().startsWith(PREFIX);}
  static boolean callerMayApprove() {return SourceApprovals.callerHolds(APPROVE_PERMISSION);}

  @Transactional
  public Result intake(Intake c,String serviceActor) {
    if(c==null||c.marginId()==null||c.marginId()<=0||!Objects.equals(c.requestKey(),PREFIX+c.marginId())
        ||c.orderId()==null||c.orderId()<=0||c.revisionId()==null||c.marginVersion()==null||c.marginVersion()<0)
      throw ApiException.badRequest("A stable OPS margin identity is required.");
    text(c.orderNumber(),60,"Order number");text(c.requestedBy(),200,"Requester");
    if(c.currency()==null||!c.currency().matches("[A-Z]{3}")||c.sellingTotal()==null||c.selectedCostTotal()==null||c.grossProfit()==null||c.marginPct()==null)
      throw ApiException.badRequest("The margin figures are required.");
    String hash=hash(c);
    var previous=em.find(OpsMarginTaskEntity.class,c.marginId(),LockModeType.PESSIMISTIC_WRITE);
    if(previous!=null){
      if(!previous.requestHash.equals(hash)) throw ApiException.conflict("This margin approval request was used for different input.");
      return new Result(previous.taskId,previous.sourceStatus);
    }
    Instant now=Instant.now();
    String id="MRG-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
    String summary="Selling "+c.sellingTotal().toPlainString()+", vendor cost "+c.selectedCostTotal().toPlainString()
        +", gross profit "+c.grossProfit().toPlainString()+" "+c.currency()+" ("+c.marginPct().toPlainString()+"%) · revision "+c.revisionId();
    var task=new TaskEntity(id,"Margin approval · "+c.orderNumber(),PREFIX+c.marginId(),"Operations",TaskType.APPROVAL,
        c.negative()?TaskPriority.HIGH:TaskPriority.MEDIUM,c.requestedBy(),null,summary,c.marginPct().toPlainString()+"%",now,null);
    task.addHistory("CREATED",serviceActor,"Submitted in OPS by "+c.requestedBy(),now,c.requestKey());
    em.persist(task);
    var b=new OpsMarginTaskEntity();b.marginId=c.marginId();b.requestKey=c.requestKey();b.orderId=c.orderId();b.revisionId=c.revisionId();
    b.marginVersion=c.marginVersion();b.orderNumber=c.orderNumber();b.requestHash=hash;b.taskId=id;b.sourceStatus="PENDING_APPROVAL";
    b.latestOpsVersion=c.marginVersion();b.serviceActor=serviceActor;b.createdAt=now;b.updatedAt=now;em.persist(b);em.flush();
    return new Result(id,b.sourceStatus);
  }

  @Transactional
  public Result state(long marginId,State s,String serviceActor) {
    if(s==null||s.opsVersion()==null||s.status()==null||!Set.of("APPROVED","REJECTED","INVALIDATED").contains(s.status()))
      throw ApiException.badRequest("A decided or invalidated OPS margin state is required.");
    if(!"INVALIDATED".equals(s.status())) text(s.actor(),200,"Decision actor");
    var b=em.find(OpsMarginTaskEntity.class,marginId,LockModeType.PESSIMISTIC_WRITE);
    // OPS delivers a margin's messages in order, so an unknown margin means its intake has not arrived yet.
    if(b==null) throw ApiException.notFound("This margin approval has not been received.");
    if(s.opsVersion()<b.latestOpsVersion||(s.opsVersion().equals(b.latestOpsVersion)&&s.status().equals(b.sourceStatus)))
      return new Result(b.taskId,b.sourceStatus); // Duplicate or older report.
    if(s.opsVersion().equals(b.latestOpsVersion)) throw ApiException.conflict("Conflicting OPS margin state at the same version.");
    var task=em.find(TaskEntity.class,b.taskId,LockModeType.PESSIMISTIC_WRITE);
    Instant now=Instant.now();String reason=s.reason()==null?null:s.reason().strip();
    if(reason!=null&&reason.length()>2000) reason=reason.substring(0,2000);
    String note=reason==null||reason.isEmpty()?null:(reason.length()>TaskHistoryEntity.MAX_COMMENT?reason.substring(0,TaskHistoryEntity.MAX_COMMENT):reason);
    switch(s.status()) {
      case "APPROVED" -> {if(!task.isClosed()) task.transition(TaskStatus.COMPLETED,now);task.addHistory("OPS_MARGIN_APPROVED",s.actor(),note,now,b.requestKey);}
      case "REJECTED" -> {if(!task.isClosed()) task.transition(TaskStatus.REJECTED,now);task.addHistory("OPS_MARGIN_REJECTED",s.actor(),note,now,b.requestKey);}
      default -> {task.transition(TaskStatus.CANCELLED,now);task.addHistory("OPS_MARGIN_INVALIDATED",serviceActor,note,now,b.requestKey);}
    }
    b.sourceStatus=s.status();b.latestOpsVersion=s.opsVersion();b.updatedAt=now;
    if(!"INVALIDATED".equals(s.status())){b.decidedBy=s.actor();b.decidedAt=s.at()==null?now:s.at();b.reason=reason;}
    em.flush();return new Result(b.taskId,b.sourceStatus);
  }

  @Transactional(readOnly=true)
  public Binding binding(String taskId) {
    if(!callerMayApprove()) throw ApiException.notFound("Task not found");
    var b=em.createQuery("from OpsMarginTaskEntity where taskId=:id",OpsMarginTaskEntity.class).setParameter("id",taskId).getResultStream().findFirst()
        .orElseThrow(()->ApiException.notFound("Task not found"));
    return new Binding(b.taskId,b.orderId,b.marginId,b.revisionId,b.marginVersion,b.orderNumber,b.sourceStatus);
  }

  private String hash(Intake c) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(c)));}catch(Exception e){throw new IllegalStateException(e);}
  }
  private static void text(String value,int max,String name){if(value==null||value.isBlank()||value.length()>max)throw ApiException.badRequest(name+" is required and must be at most "+max+" characters.");}
}
