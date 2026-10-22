package com.orisenc.workflow.task;

import com.orisenc.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Sales-only intake/state for quote approval tasks, and the approver's read of a task's Sales identity. */
@RestController @RequestMapping("/api/sales-quote-approval-tasks")
public class SalesQuoteTaskController {
  static final String SYNC="platform.quoteapproval.sync";
  private final SalesQuoteTaskService service;private final CurrentUserProvider users;
  public SalesQuoteTaskController(SalesQuoteTaskService service,CurrentUserProvider users){this.service=service;this.users=users;}
  @PostMapping @PreAuthorize("hasAuthority('"+SYNC+"')")
  public SalesQuoteTaskService.Result intake(@RequestBody SalesQuoteTaskService.Intake body){return service.intake(body,SourceApprovals.serviceActor(users));}
  @PostMapping("/{requestKey}/state") @PreAuthorize("hasAuthority('"+SYNC+"')")
  public SalesQuoteTaskService.Result state(@PathVariable String requestKey,@RequestBody SalesQuoteTaskService.State body){return service.state(requestKey,body,SourceApprovals.serviceActor(users));}
  @GetMapping("/by-task/{taskId}") @PreAuthorize("hasAuthority('"+TaskPermissions.VIEW+"')")
  public SalesQuoteTaskService.Binding binding(@PathVariable String taskId){return service.binding(taskId);}
}
