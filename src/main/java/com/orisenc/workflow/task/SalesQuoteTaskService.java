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
 * Sales quote approval tasks (owner decisions, 22 Oct 2026). Sales submits each approval round once (intake)
 * and reports its decision or cancellation (state). The task is open to every holder of sales.quote.approve,
 * has no due date, and is never decided here: the browser sends the TL's decision to Sales with the TL's own
 * token, and Sales's state message closes the task. Same shape as {@link OpsMarginTaskService}.
 */
@Service
public class SalesQuoteTaskService {
  static final String PREFIX = "sales-quote-";
  static final String APPROVE_PERMISSION = "sales.quote.approve";
  private final EntityManager em;private final ObjectMapper json;
  public SalesQuoteTaskService(EntityManager em,ObjectMapper json){this.em=em;this.json=json;}

  public record Intake(String requestKey,Long quoteId,Integer round,String quoteCode,String subject,String accountName,
      String ownerUserId,String requestedBy,String currency,BigDecimal grandTotal,BigDecimal costingGrandTotal,Instant submittedAt) {}
  /** sequence orders a round's reports: 1 for its decision or a cancellation while pending, 2 for a cancellation after approval. */
  public record State(Integer sequence,String status,String actor,Instant at,String reason) {}
  public record Result(String taskId,String sourceStatus) {}
  public record Binding(String taskId,Long quoteId,Integer round,String quoteCode,String sourceStatus) {}

  static String requestKey(long quoteId,int round){return PREFIX+quoteId+"-"+round;}

  @Transactional
  public Result intake(Intake c,String serviceActor) {
    if(c==null||c.quoteId()==null||c.quoteId()<=0||c.round()==null||c.round()<=0||!Objects.equals(c.requestKey(),requestKey(c.quoteId(),c.round())))
      throw ApiException.badRequest("A stable Sales quote approval identity is required.");
    text(c.quoteCode(),40,"Quote code");text(c.requestedBy(),200,"Requester");text(c.ownerUserId(),200,"Quote owner");
    if(!"INR".equals(c.currency())||c.grandTotal()==null)
      throw ApiException.badRequest("The quote figures are required.");
    String hash=hash(c);
    var previous=em.find(SalesQuoteTaskEntity.class,c.requestKey(),LockModeType.PESSIMISTIC_WRITE);
    if(previous!=null){
      if(!previous.requestHash.equals(hash)) throw ApiException.conflict("This quote approval request was used for different input.");
      return new Result(previous.taskId,previous.sourceStatus);
    }
    Instant now=Instant.now();
    String id="QAP-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
    String summary=join(c.accountName(),c.subject())+"Grand total "+c.grandTotal().toPlainString()+" "+c.currency()
        +(c.costingGrandTotal()==null||c.costingGrandTotal().signum()==0?"":", costing "+c.costingGrandTotal().toPlainString()+" "+c.currency())
        +" · owner "+c.ownerUserId()+" · round "+c.round();
    if(summary.length()>2000) summary=summary.substring(0,2000);
    var task=new TaskEntity(id,"Quote approval · "+c.quoteCode(),c.requestKey(),"Sales",TaskType.APPROVAL,TaskPriority.MEDIUM,
        c.requestedBy(),null,summary,c.grandTotal().toPlainString()+" "+c.currency(),now,null);
    task.addHistory("CREATED",serviceActor,"Submitted in Sales by "+c.requestedBy(),now,c.requestKey());
    em.persist(task);
    var b=new SalesQuoteTaskEntity();b.requestKey=c.requestKey();b.quoteId=c.quoteId();b.round=c.round();b.quoteCode=c.quoteCode();
    b.requestHash=hash;b.taskId=id;b.sourceStatus="PENDING";b.latestSequence=0;b.serviceActor=serviceActor;b.createdAt=now;b.updatedAt=now;
    em.persist(b);em.flush();
    return new Result(id,b.sourceStatus);
  }

  @Transactional
  public Result state(String requestKey,State s,String serviceActor) {
    if(s==null||s.sequence()==null||s.sequence()<=0||s.status()==null||!Set.of("APPROVED","REJECTED","CANCELLED").contains(s.status()))
      throw ApiException.badRequest("A decided or cancelled quote approval state is required.");
    if(!"CANCELLED".equals(s.status())) text(s.actor(),200,"Decision actor");
    var b=em.find(SalesQuoteTaskEntity.class,requestKey,LockModeType.PESSIMISTIC_WRITE);
    // Sales delivers a quote's messages in order, so an unknown request means its intake has not arrived yet.
    if(b==null) throw ApiException.notFound("This quote approval has not been received.");
    if(s.sequence()<b.latestSequence||(s.sequence().equals(b.latestSequence)&&s.status().equals(b.sourceStatus)))
      return new Result(b.taskId,b.sourceStatus); // Duplicate or older report.
    if(s.sequence().equals(b.latestSequence)) throw ApiException.conflict("Conflicting Sales quote approval state at the same sequence.");
    var task=em.find(TaskEntity.class,b.taskId,LockModeType.PESSIMISTIC_WRITE);
    Instant now=Instant.now();String reason=s.reason()==null?null:s.reason().strip();
    if(reason!=null&&reason.length()>2000) reason=reason.substring(0,2000);
    String note=reason==null||reason.isEmpty()?null:(reason.length()>TaskHistoryEntity.MAX_COMMENT?reason.substring(0,TaskHistoryEntity.MAX_COMMENT):reason);
    switch(s.status()) {
      case "APPROVED" -> {if(!task.isClosed()) task.transition(TaskStatus.COMPLETED,now);task.addHistory("SALES_QUOTE_APPROVED",s.actor(),note,now,b.requestKey);}
      case "REJECTED" -> {if(!task.isClosed()) task.transition(TaskStatus.REJECTED,now);task.addHistory("SALES_QUOTE_REJECTED",s.actor(),note,now,b.requestKey);}
      default -> {task.transition(TaskStatus.CANCELLED,now);task.addHistory("SALES_QUOTE_CANCELLED",serviceActor,note,now,b.requestKey);}
    }
    b.sourceStatus=s.status();b.latestSequence=s.sequence();b.updatedAt=now;
    if(!"CANCELLED".equals(s.status())){b.decidedBy=s.actor();b.decidedAt=s.at()==null?now:s.at();b.reason=reason;}
    em.flush();return new Result(b.taskId,b.sourceStatus);
  }

  @Transactional(readOnly=true)
  public Binding binding(String taskId) {
    if(!SourceApprovals.callerHolds(APPROVE_PERMISSION)) throw ApiException.notFound("Task not found");
    var b=em.createQuery("from SalesQuoteTaskEntity where taskId=:id",SalesQuoteTaskEntity.class).setParameter("id",taskId).getResultStream().findFirst()
        .orElseThrow(()->ApiException.notFound("Task not found"));
    return new Binding(b.taskId,b.quoteId,b.round,b.quoteCode,b.sourceStatus);
  }

  private static String join(String account,String subject){
    var parts=new ArrayList<String>();
    if(account!=null&&!account.isBlank()) parts.add(account.strip());
    if(subject!=null&&!subject.isBlank()) parts.add(subject.strip());
    return parts.isEmpty()?"":String.join(" · ",parts)+" · ";
  }
  private String hash(Intake c) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(c)));}catch(Exception e){throw new IllegalStateException(e);}
  }
  private static void text(String value,int max,String name){if(value==null||value.isBlank()||value.length()>max)throw ApiException.badRequest(name+" is required and must be at most "+max+" characters.");}
}
