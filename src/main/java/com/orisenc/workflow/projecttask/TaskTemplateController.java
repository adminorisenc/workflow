package com.orisenc.workflow.projecttask;
import static com.orisenc.workflow.projecttask.ProjectTaskDtos.*;
import org.springframework.web.bind.annotation.*; import org.springframework.security.access.prepost.PreAuthorize; import java.util.*;
@RestController @RequestMapping("/api/task-templates")
public class TaskTemplateController {
 private final TaskTemplateService service; public TaskTemplateController(TaskTemplateService service){this.service=service;}
 @GetMapping @PreAuthorize("hasAuthority('platform.work.create')") public List<TaskTemplateResponse> list(){return service.list();}
 @GetMapping("/{id}") @PreAuthorize("hasAuthority('platform.work.create')") public TaskTemplateResponse get(@PathVariable Long id){return service.get(id);}
 @PostMapping @PreAuthorize("hasAuthority('platform.work.templates.manage')") public TaskTemplateResponse create(@RequestBody TaskTemplateRequest r){return service.create(r);}
 @PutMapping("/{id}") @PreAuthorize("hasAuthority('platform.work.templates.manage')") public TaskTemplateResponse update(@PathVariable Long id,@RequestBody TaskTemplateRequest r){return service.update(id,r);}
 @PostMapping("/{id}/activate") @PreAuthorize("hasAuthority('platform.work.templates.manage')") public TaskTemplateResponse activate(@PathVariable Long id){return service.activate(id,true);}
 @PostMapping("/{id}/deactivate") @PreAuthorize("hasAuthority('platform.work.templates.manage')") public TaskTemplateResponse deactivate(@PathVariable Long id){return service.activate(id,false);}
}
