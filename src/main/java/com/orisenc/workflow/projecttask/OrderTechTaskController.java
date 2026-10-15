package com.orisenc.workflow.projecttask;
import com.orisenc.security.CurrentUserProvider;
import com.orisenc.workflow.api.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/order-tech-tasks")
public class OrderTechTaskController {
  private final OrderTechTaskService service;private final CurrentUserProvider users;
  public OrderTechTaskController(OrderTechTaskService service,CurrentUserProvider users){this.service=service;this.users=users;}
  @PostMapping @PreAuthorize("hasAuthority('platform.work.order.create')")
  public OrderTechTaskService.Result create(@RequestBody OrderTechTaskService.Command body){
    String actor=users.get().objectId();if(actor==null||actor.isBlank())throw ApiException.forbidden("A service identity is required.");
    return service.create(body,actor);
  }
}
