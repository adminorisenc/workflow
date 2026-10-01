package com.orisenc.workflow.projecttask;

import com.orisenc.workflow.api.ApiException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ChecklistTemplateService {
  private final ChecklistTemplateRepository repository;
  private final Clock clock;

  @Autowired
  public ChecklistTemplateService(ChecklistTemplateRepository repository) {
    this(repository, Clock.systemUTC());
  }

  ChecklistTemplateService(ChecklistTemplateRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ChecklistTemplateResponse> list(boolean includeInactive) {
    if (includeInactive) requireManage();
    // Interim TM-030 policy: every task creator sees every active template until per-template
    // audiences are decided. The backend still requires WORK_CREATE on the controller.
    return (includeInactive ? repository.findAllByOrderByActiveDescNameAsc()
        : repository.findAllByActiveTrueOrderByNameAsc()).stream().map(ChecklistTemplateService::response).toList();
  }

  @Transactional(readOnly = true)
  public ChecklistTemplateResponse get(long id) {
    var template = find(id);
    if (!template.isActive()) requireManage();
    return response(template);
  }

  @Transactional
  public ChecklistTemplateResponse create(ChecklistTemplateWriteRequest request, String actor) {
    requireManage();
    if (request == null) throw ApiException.badRequest("A checklist template body is required.");
    assertNameAvailable(request.name(), null);
    try {
      return response(repository.saveAndFlush(new ChecklistTemplateEntity(request.name(), request.description(),
          request.items(), actor, clock.instant())));
    } catch (IllegalArgumentException rejected) { throw ApiException.badRequest(rejected.getMessage()); }
  }

  @Transactional
  public ChecklistTemplateResponse update(long id, ChecklistTemplateWriteRequest request, String actor) {
    requireManage();
    var template = find(id);
    assertVersion(template, request == null ? null : request.expectedVersion());
    if (request == null) throw ApiException.badRequest("A checklist template body is required.");
    if (template.isActive()) assertNameAvailable(request.name(), template.getId());
    try { template.revise(request.name(), request.description(), request.items(), actor, clock.instant()); }
    catch (IllegalArgumentException rejected) { throw ApiException.badRequest(rejected.getMessage()); }
    return response(repository.saveAndFlush(template));
  }

  @Transactional
  public ChecklistTemplateResponse activate(long id, Long expectedVersion, String actor) {
    requireManage();
    var template = find(id); assertVersion(template, expectedVersion);
    assertNameAvailable(template.getName(), template.getId());
    template.activate(actor, clock.instant()); return response(repository.saveAndFlush(template));
  }

  @Transactional
  public ChecklistTemplateResponse deactivate(long id, Long expectedVersion, String actor) {
    requireManage();
    var template = find(id); assertVersion(template, expectedVersion);
    template.deactivate(actor, clock.instant()); return response(repository.saveAndFlush(template));
  }

  ChecklistTemplateEntity activeEntity(long id) {
    var template = find(id);
    if (!template.isActive()) throw ApiException.badRequest("That checklist template is inactive.");
    return template;
  }

  private ChecklistTemplateEntity find(long id) {
    return repository.findById(id).orElseThrow(() -> ApiException.notFound("Checklist template not found."));
  }
  private static void requireManage() {
    ProjectTaskPermissions.require(ProjectTaskPermissions.TEMPLATES_MANAGE,
        "Your role does not permit managing checklist templates.");
  }
  private void assertNameAvailable(String name, Long excludingId) {
    if (name != null && repository.activeNameExists(name.trim(), excludingId))
      throw ApiException.conflict("An active checklist template already uses that name.");
  }
  private static void assertVersion(ChecklistTemplateEntity template, Long expected) {
    if (expected == null) throw ApiException.badRequest("expectedVersion is required.");
    if (template.getVersion() != expected)
      throw ApiException.conflict("This checklist template changed after you opened it. Refresh and try again.");
  }
  static ChecklistTemplateResponse response(ChecklistTemplateEntity template) {
    return new ChecklistTemplateResponse(template.getId(), template.getVersion(), template.getName(),
        template.getDescription(), template.isActive(), template.getCreatedBy(), template.getCreatedAt(),
        template.getUpdatedBy(), template.getUpdatedAt(), template.getItems().stream()
            .map(item -> new ChecklistTemplateItemResponse(item.getId(), item.getSequenceNo(), item.getTitle(),
                item.isRequired())).toList());
  }
}
