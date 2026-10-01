package com.orisenc.workflow.projecttask;

import com.orisenc.security.CurrentUserProvider;
import com.orisenc.workflow.api.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/checklist-templates")
public class ChecklistTemplateController {
  private final ChecklistTemplateService service;
  private final CurrentUserProvider currentUser;
  public ChecklistTemplateController(ChecklistTemplateService service, CurrentUserProvider currentUser) {
    this.service = service; this.currentUser = currentUser;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.CREATE + "')")
  public List<ChecklistTemplateResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
    return service.list(includeInactive);
  }
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.CREATE + "')")
  public ChecklistTemplateResponse get(@PathVariable long id) { return service.get(id); }
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.TEMPLATES_MANAGE + "')")
  public ChecklistTemplateResponse create(@RequestBody ChecklistTemplateWriteRequest request) {
    return service.create(request, actor());
  }
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.TEMPLATES_MANAGE + "')")
  public ChecklistTemplateResponse update(@PathVariable long id,
      @RequestBody ChecklistTemplateWriteRequest request) { return service.update(id, request, actor()); }
  @PostMapping("/{id}/activate")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.TEMPLATES_MANAGE + "')")
  public ChecklistTemplateResponse activate(@PathVariable long id, @RequestBody VersionRequest request) {
    return service.activate(id, request == null ? null : request.expectedVersion(), actor());
  }
  @PostMapping("/{id}/deactivate")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.TEMPLATES_MANAGE + "')")
  public ChecklistTemplateResponse deactivate(@PathVariable long id, @RequestBody VersionRequest request) {
    return service.deactivate(id, request == null ? null : request.expectedVersion(), actor());
  }
  record VersionRequest(Long expectedVersion) {}
  private String actor() {
    String actor = currentUser.get().preferredUsername();
    if (actor == null || actor.isBlank()) throw ApiException.forbidden("A valid signed-in identity is required.");
    return actor;
  }
}
