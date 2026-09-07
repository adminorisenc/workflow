package com.orisenc.workflow.sla;

import com.orisenc.workflow.projecttask.ProjectTaskPermissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An operator's handle on the SLA pass.
 *
 * <p>Exists because a quarter-hourly scheduled job is otherwise unobservable until somebody either
 * gets a message or does not, and "did the ladder work" should not be a question answered by waiting
 * fifteen minutes. It runs exactly what the schedule runs and returns the same summary, so a support
 * engineer can trigger a pass, read the counts and go looking in the Integration Service's delivery
 * log for what it raised.
 *
 * <p>Behind {@code platform.work.manage} rather than a view permission: this writes escalation
 * levels and sends people mail, which is team-lead work rather than something anyone who can read a
 * queue should be able to set off.
 */
@RestController
@RequestMapping(path = "/api/sla", produces = "application/json")
public class SlaController {

  private final SlaEscalationService escalations;

  public SlaController(SlaEscalationService escalations) {
    this.escalations = escalations;
  }

  /**
   * Runs one pass now.
   *
   * <p>Safe to call repeatedly: a rung already recorded on an item is not raised again, so an
   * impatient second click costs a scan and sends nothing.
   */
  @PostMapping("/run")
  @PreAuthorize("hasAuthority('" + ProjectTaskPermissions.MANAGE + "')")
  public SlaEscalationService.SlaRun run() {
    return escalations.runOnce();
  }
}
