package com.orisenc.workflow.task;

import com.orisenc.security.CurrentUserProvider;
import com.orisenc.workflow.api.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** OPS-only intake/state for margin approval tasks, and the approver's read of a task's OPS identity. */
@RestController @RequestMapping("/api/ops-margin-approval-tasks")
public class OpsMarginTaskController {
  static final String SYNC="platform.margin.sync";
  private final OpsMarginTaskService service;private final CurrentUserProvider users;
  public OpsMarginTaskController(OpsMarginTaskService service,CurrentUserProvider users){this.service=service;this.users=users;}
  @PostMapping @PreAuthorize("hasAuthority('"+SYNC+"')")
  public OpsMarginTaskService.Result intake(@RequestBody OpsMarginTaskService.Intake body){return service.intake(body,serviceActor());}
  @PostMapping("/{marginId}/state") @PreAuthorize("hasAuthority('"+SYNC+"')")
  public OpsMarginTaskService.Result state(@PathVariable long marginId,@RequestBody OpsMarginTaskService.State body){return service.state(marginId,body,serviceActor());}
  @GetMapping("/by-task/{taskId}") @PreAuthorize("hasAuthority('"+TaskPermissions.VIEW+"')")
  public OpsMarginTaskService.Binding binding(@PathVariable String taskId){return service.binding(taskId);}
  private String serviceActor(){
    var u=users.get();
    // A person never syncs OPS state, even one holding the permission: only an application token has no sign-in name.
    if(u.objectId()==null||u.objectId().isBlank()||(u.preferredUsername()!=null&&!u.preferredUsername().isBlank()))
      throw ApiException.forbidden("A service identity is required.");
    return u.objectId();
  }
}
