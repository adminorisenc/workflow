package com.orisenc.workflow.delegation;

import static com.orisenc.workflow.delegation.DelegationDtos.*;

import com.orisenc.security.CurrentUserProvider;
import com.orisenc.workflow.api.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST layer for delegation (REQ-0027).
 *
 * <p>Endpoint-level authorization here is the coarse half only. Whether a caller may see or end a
 * <em>particular</em> delegation depends on whether they are one of the two people in it, which is a
 * record-level decision and belongs in {@link DelegationService} - the same division the work item
 * API follows.
 */
@RestController
@RequestMapping(path = "/api/delegations", produces = "application/json")
public class DelegationController {

  private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

  private final DelegationService service;
  private final DelegationExpiryService expiry;
  private final CurrentUserProvider currentUser;

  public DelegationController(DelegationService service, DelegationExpiryService expiry,
      CurrentUserProvider currentUser) {
    this.service = service;
    this.expiry = expiry;
    this.currentUser = currentUser;
  }

  /**
   * @param delegator whose work; {@code me} resolves to the caller server-side
   * @param delegate who is standing in; {@code me} resolves to the caller server-side
   * @param inForce true to return only delegations authorizing somebody at this moment
   */
  @GetMapping
  @PreAuthorize("hasAuthority('" + DelegationPermissions.VIEW + "')")
  public List<DelegationResponse> list(
      @RequestParam(required = false) DelegationStatus status,
      @RequestParam(required = false) String delegator,
      @RequestParam(required = false) String delegate,
      @RequestParam(required = false) Boolean inForce,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.list(new DelegationService.ListQuery(status, delegator, delegate, inForce, page, size),
        actor());
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('" + DelegationPermissions.VIEW + "')")
  public DelegationResponse get(@PathVariable String id) {
    return service.get(id, actor());
  }

  @PostMapping(consumes = "application/json")
  @PreAuthorize("hasAuthority('" + DelegationPermissions.CREATE + "')")
  public ResponseEntity<DelegationResponse> create(@RequestBody CreateDelegationRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return ResponseEntity.status(201).body(service.create(body, actor(), correlationId(correlationId)));
  }

  @PostMapping(path = "/{id}/approve", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + DelegationPermissions.APPROVE + "')")
  public DelegationResponse approve(@PathVariable String id, @RequestBody DecisionRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.approve(id, body, actor(), correlationId(correlationId));
  }

  @PostMapping(path = "/{id}/reject", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + DelegationPermissions.APPROVE + "')")
  public DelegationResponse reject(@PathVariable String id, @RequestBody DecisionRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.reject(id, body, actor(), correlationId(correlationId));
  }

  /**
   * Ends a delegation early.
   *
   * <p>Only the view permission at this level, deliberately. Taking back your own authority is not
   * an administrative act, and the service refuses anyone who is neither the delegator nor a holder
   * of {@code platform.delegation.manage}.
   */
  @PostMapping(path = "/{id}/revoke", consumes = "application/json")
  @PreAuthorize("hasAuthority('" + DelegationPermissions.VIEW + "')")
  public DelegationResponse revoke(@PathVariable String id, @RequestBody DecisionRequest body,
      @RequestHeader(value = CORRELATION_ID_HEADER, required = false) String correlationId) {
    return service.revoke(id, body, actor(), correlationId(correlationId));
  }

  /**
   * Runs the expiry pass now.
   *
   * <p>The same operator handle as {@code POST /api/sla/run}, and for the same reason: an hourly job
   * is otherwise unobservable, and "did that delegation actually close" should not be a question
   * answered by waiting.
   */
  @PostMapping("/expire-run")
  @PreAuthorize("hasAuthority('" + DelegationPermissions.MANAGE + "')")
  public DelegationExpiryService.ExpiryRun expireRun() {
    return expiry.runOnce();
  }

  /** The acting identity, from the token's {@code preferred_username} and never the request body. */
  private String actor() {
    String name = currentUser.get().preferredUsername();
    if (name == null || name.isBlank())
      throw ApiException.forbidden("A valid signed-in identity is required.");
    return name;
  }

  private String correlationId(String supplied) {
    return supplied != null && !supplied.isBlank() ? supplied : UUID.randomUUID().toString();
  }
}
