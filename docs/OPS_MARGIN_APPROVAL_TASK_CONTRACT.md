# OPS margin approval task contract

Owner decision, 22 October 2026: margin approval is disabled; its implementation is retained in comments or unregistered components. Sales quote approval remains active. Vendor comparison, current margin calculations and Operations accept/return remain active. Historical margin evaluations and tasks are retained; legacy margin tasks are hidden and cannot be acted on. Applied migrations remain unchanged; Common Platform V17 revokes margin grants. The margin-approval design below is historical and superseded by this decision.


**Implemented 22 October 2026 with the owner's answers to §5:** the browser sends the TL's decision to
OPS with the TL's own OPS token (no OBO); the task is unassigned and open to every holder of
`operations.margin.approve`; no due date; invalidation closes the task as the new `CANCELLED` status.
Built: `POST /api/ops-margin-approval-tasks` (intake), `POST /api/ops-margin-approval-tasks/{marginId}/state`
and `GET /api/ops-margin-approval-tasks/by-task/{taskId}`, guarded by `platform.margin.sync` (role
`OPS_MARGIN_SYNC`, application tokens only) or task view plus margin approval. Workflow V4, OPS V9
(ordered outbox), Common Platform V14. Generic task actions refuse margin tasks; non-approvers get 404.
Not built from this proposal: OBO, reconciliation job, terminal-before-intake tombstones (OPS sends
state only after an intake and delivers each margin's messages in order). The design below is kept
for reference.

Status: proposed design, 22 October 2026. No implementation or deployment is authorized by this document. D-4 is decided: a TL may decide from OPS or a Workflow approval task; OPS owns the evaluation and decision. Endpoint names introduced below are proposals requiring agreement with OPS.

## Sources and existing constraints

Read against `../../docs/OPS_VENDOR_MARGIN_PLAN.md` sections 5 and 7, `../../MIGRATION_REST_CONTRACTS.md` “Margin evaluation and approval”, Workflow `projecttask/OrderTechTaskController`, `OrderTechTaskService`, `OrderTechTaskEntity`, their tests and V2 migration, and the `task` approvals module (`TaskController`, `TaskService`, `TaskEntity`, DTOs and permissions). The Stage 1 OPS worktree's `MarginController`, `MarginService` and `OrderMargin` were inspected read-only to resolve version semantics; that work is owned by another session and may change.

The Tech intake demonstrates stable request identity, payload hash, transactional creation and replay returning the existing string task ID. Its key is order-scoped and creates a `project_task`; margin intake must instead create a `workflow_task` of type `APPROVAL`, keyed per evaluation. Existing approvals complete locally, use a Workflow `expectedVersion`, require assignee/delegate checks, and have no cancellation state. They cannot be reused unchanged for an OPS-backed decision. Existing generic list/get currently filter records without a per-record visibility policy; margin tasks need a backend visibility policy before their financial snapshots enter those responses.

OPS `orderId`, `marginId` and revision IDs are numeric Long identities; Workflow task IDs are strings. Entra user object IDs, service-principal object IDs, application client IDs and task assignee UPNs are distinct. Do not substitute one for another. OPS decision `expectedVersion` is **OrderMargin.version**, exposed as `Evaluation.version`, not SalesOrder.version, revision ID or Workflow task.version.

## 1. OPS to Workflow intake

Proposed `POST /workflow-service/ws/api/ops-margin-approval-tasks` requires new permission `platform.task.ops-margin.sync` and an authenticated, explicitly allowed OPS application principal. Grant it only to the dedicated OPS service role/principal; never grant it through a broad human admin catalogue or generic PLATFORM_SERVICE bundle. An objectId being present is insufficient proof of an application caller. Validate tenant, Workflow audience, application role, principal type and configured OPS application identity as well as resolved permission, fail-closed. This is the service-only analogue of `platform.work.order.create`, not a reuse of the Tech permission.

Proposed command fields:

| Field | Contract |
| --- | --- |
| requestKey | Exactly `ops-margin-<marginId>`; canonical decimal ID, no user-generated alternative key |
| orderId, marginId, revisionId | Positive numeric IDs identifying the frozen OPS evaluation |
| marginVersion | Non-negative OPS evaluation version at submission; immutable intake value |
| orderNumber | Display reference; never an identity or routing key |
| requestedBy | Submitted-by identity snapshot from OPS; record separately from authenticated delivery principal |
| assignee | Active, explicitly resolved TL UPN; not permission conveyed by payload |
| dueAt | UTC instant; policy unresolved below |
| evaluation | Frozen OPS currency, ex-GST totals, gross profit, margin percentage, negative flag and submitted timestamp; decimal values, no Workflow recalculation |

OPS writes an immutable delivery command/outbox in the same transaction as a new margin submission and retries after commit with bounded backoff and visible delivery failure. Same-figures submission returning an existing margin must reuse its key; rejection/resubmission producing a new margin gets a new key, even for the same order. Workflow outage must not lose the pending approval or trigger a second evaluation. OPS remains independently decidable while delivery is pending.

Workflow atomically persists a source binding, deduplication hash, task and creation history. Unique `(source, margin_id)` and unique request key enforce one task under concurrent retries. Normalize a defined canonical command before hashing; exclude delivery metadata such as correlation IDs and attempt timestamps. Identical replay returns `200 {taskId}` without another history/notification; first create also returns `200 {taskId}`, matching Tech intake style. Changed immutable payload for the same key returns 409 and is a delivery-contract failure, not permission to mint a different key. Assignment changes need a separate audited operation, not a changed retry command. Invalid input is 400; disallowed principal 403; invalid token 401; directory/permission resolution unavailable fails closed without partial creation.

Handle unique-key races by retrying lookup in a fresh transaction after the losing insert rolls back, then comparing the hash. Do not catch a constraint violation and continue in a rollback-only transaction.

## 2. A decision from Workflow

Recommended identity transport: the browser calls a dedicated Workflow endpoint using its Workflow-audience delegated token; Workflow exchanges it through Entra on-behalf-of (OBO) for an OPS-audience delegated token and calls OPS synchronously as the **actual TL**. Never forward a Workflow-audience token to OPS. Never use a daemon decision token with a caller-supplied actor header/body. The client secret, if required for OBO, stays server-side; tokens are never persisted or logged. OBO setup is an open implementation prerequisite, not an existing client capability.

Proposed `POST /api/tasks/{taskId}/ops-margin-decision` body:

```json
{"expectedTaskVersion": 3, "marginId": 42, "expectedMarginVersion": 0, "decision": "APPROVE", "reason": null}
```

Workflow resolves order ID from its immutable binding, verifies the supplied margin ID equals that binding and the displayed evaluation, and checks local version, open state, assignee/authorized-delegate eligibility, `platform.task.view`, `platform.task.approve` and `operations.margin.approve`. A delegate must personally hold OPS approval permission and pass OPS order scope; delegation never lends an authority. Claim/start must also be restricted to eligible OPS approvers. TL self-approval is permitted; no user-level approval and no EXCEPTION action. REJECT requires a reason. Use the dedicated OPS-compatible 2000-character reason limit, leaving the existing generic 500-character comment contract unchanged; persist the full reason in the binding audit.

Workflow then calls `POST /operations-service/ws/api/orders/{boundOrderId}/margin/decision` with:

```json
{"marginId": 42, "expectedVersion": 0, "decision": "APPROVE", "reason": null}
```

These are the exact evaluation ID and OPS version the TL reviewed. Never silently replace either with the latest current margin/version. OPS independently enforces `operations.margin.approve`, existing order scope, current margin identity, version, PENDING_APPROVAL and fresh selection fingerprint, records the real actor, and commits its history. No permission or attribution comes from the intake assignee/requester. OPS serializes competing decisions and invalidation; at most one decision transition succeeds, every second decision gets 409, including the same choice. This is an exactly-once business effect over potentially repeated transport, not exactly-once network delivery.

Only after OPS confirms acceptance does Workflow project APPROVED as task COMPLETED or REJECTED as task REJECTED, with OPS decision actor/time/reason and the authenticated syncing identity separately retained. Generic `/api/tasks/{id}/actions` must dispatch source-backed decisions through this orchestration or reject them with a business-rule error pointing to the dedicated operation. It must never locally approve/reject/complete such a task. Generic creation cannot fabricate a source binding; no edit/reassignment may change its OPS identity.

Do not hold a Workflow database write transaction across the OPS HTTP call. Reserve an audited attempt using local optimistic concurrency, call OPS, then finalize in a fresh transaction. Concurrent callback/local finalization uses the same projection logic. A Workflow rollback or outage after OPS commits is repaired by synchronization; it cannot roll OPS back. On timeout the outcome is unknown: retain a reconciling state, disable another decision until resolved, and read OPS before any retry. Do not durably replay a user's decision later under a service identity. A fresh authorized user request may retry the exact original pair only after reconciliation; it must never retarget a newer evaluation.

## 3. OPS-first closure, invalidation and recovery

Proposed `POST /api/ops-margin-approval-tasks/{marginId}/state` uses the same OPS-only synchronization permission and principal checks. OPS emits durable state messages transactionally with approval/rejection/invalidation, retried after commit. Payload: `eventId`, `orderId`, `marginId`, `marginVersion`, `status`, authoritative decision actor/time/reason, invalidation time/cause and correlation reference. These are authenticated OPS assertions, not browser-writable task actions.

Store latest applied OPS version and deduplicate event IDs. Duplicate/equivalent state is acknowledged without duplicate history/notifications. Older versions are ignored; conflicting state at the same version is quarantined for reconciliation, never last-writer-wins. Check order/margin binding, supported transitions and complete required evidence before applying. A late approval must not overwrite a newer INVALIDATED projection.

| OPS observation | Workflow projection |
| --- | --- |
| APPROVED | COMPLETED, audit `OPS_MARGIN_APPROVED` |
| REJECTED | REJECTED, audit `OPS_MARGIN_REJECTED` |
| INVALIDATED | CANCELLED, audit `OPS_MARGIN_INVALIDATED`; preserve any prior approval evidence |
| PENDING_APPROVAL and current | Open, subject to assignment and permissions |

CANCELLED is a proposed additive TaskStatus, not currently supported. Exclude it from actions, open SLA candidates and overdue counts, set closure timestamp, and preserve history. Invalidation after approval changes the projection to CANCELLED while retaining the prior decision. Do not encode invalidation as a human rejection. Margin decisions and task projection never change OPS reviewStatus, shipping or release eligibility themselves.

State may arrive before intake, especially if OPS decides while Workflow is offline. Persist a terminal tombstone/binding keyed by margin ID even without task ID; late intake creates an already-closed projection atomically (or returns the existing projection), never a newly actionable task. Keep initial command hash separate from evolving source state. A newer evaluation creates its own task and cannot reopen the old one.

A scheduled Workflow reconciliation pass repairs lost callbacks and unknown outcomes through an authenticated OPS read client; reconcile on task open/decision recovery as well. Read the bound margin in OPS `history` if `current` points to a newer row. Current-pointer mismatch alone must not invent an approval/rejection or close the replacement task. Unknown/missing rows stay non-actionable with a visible reconciliation failure. OPS read outage leaves existing evidence intact and queues retry. Service read authorization must be agreed explicitly; a general user-scoped GET cannot be assumed to work for a daemon.

### Conflict and failure handling

- Local task version mismatch: 409 before calling OPS; reload the task.
- OPS 409: propagate conflict using the existing Workflow error envelope, fetch authoritative OPS current/history and synchronize the bound row. An already-decided or invalidated row closes accordingly. If still pending, show the conflict and require a fresh review; never auto-refresh the decision version and resubmit. OPS may commit fingerprint invalidation even while returning 409.
- Intake 409: preserve the original task; flag payload mismatch for operator correction. It is not a retryable transient failure.
- OPS 400/401/403: preserve open task, return actionable validation/access failure; do not mark approved or retry with broader credentials.
- OPS 5xx/timeout: record unknown/retryable read-reconciliation state, not decision success. A lost success response is resolved from OPS, and must not create a second decision or claim the current user was the winner without matching OPS audit evidence.

## 4. Required implementation work, migrations and tests

Workflow needs a source-aware approval adapter, dedicated decision DTO, OBO OPS client, source-state endpoint, reconciliation worker and visibility/action policy. Preserve existing generic task paths, payloads, permissions and non-OPS transitions. The margin-specific task read must carry bound order/margin, displayed OPS version and synchronization state; use an additive source-detail endpoint rather than changing the frozen generic task response. Prevent exposure through generic list/get to users outside the intended OPS record scope, including snapshot fields and history. Broad Workflow approval permission alone is insufficient.

Permissions required at implementation:

- Dedicated OPS application role on Workflow API plus OPS-only `platform.task.ops-margin.sync`; deliberate exclusion from human roles including PLATFORM_ADMIN. Check catalogue-derived admin behavior explicitly.
- OPERATIONS_TL and PLATFORM_ADMIN need the existing Workflow view/approve (and execute if claim/start remains) plus `operations.margin.approve`; generic WORKFLOW_APPROVER or department USER must gain no margin decision authority. OPS order access remains enforced.
- OBO delegated OPS access/admin consent and Workflow confidential-client configuration. Background read uses a separate narrowly scoped application role/permission with explicit record scope; no application approval grant.
- Future Common Platform catalogue/seeder, role-grant migration and UI permission constants/enum consumers must stay aligned. These are coordinated follow-up changes, not edits authorized here; no Common Platform/UI restart in this task.

Use a new Workflow Flyway migration numbered after the latest migration at implementation time (V3 activity work is already present and unrelated). Add an `ops_margin_approval_task` binding with numeric source IDs, unique margin/request/task keys, immutable canonical command hash, initial and latest OPS versions, local optimistic version, source status, reconciliation/attempt state, actor/time/reason evidence and timestamps. Add durable state inbox/tombstone and deduplication storage as needed, with retry indexes and local foreign keys only. Keep terminal-before-intake task ID nullable until projection exists. Any new workflow_task source marker, cancellation storage/constraints or audit columns require that same forward migration. Preserve existing rows as ordinary local tasks; do not backfill source identity from display references. Never change existing applied migrations or use cross-database foreign keys.

OPS follow-up requires transactional intake/state outboxes and restricted read authorization; Common Platform needs permission/grant migration plus catalogue updates. Select migration numbers independently in each owning repository; do not reserve them in this document.

Required tests before release:

1. Controller/security tests: wrong audience/tenant/principal, human admin with sync permission, missing application role, spoofed actor and resolver outage all fail closed; a permitted OPS principal succeeds. Unauthorized generic list/get and action paths cannot disclose or decide a margin task.
2. Persistence/concurrency: identical replay and concurrent deliveries yield one binding/task/history notification; changed payload conflicts; rollback leaves no orphan; new margin on same order creates another task; same-figures submission does not. Terminal-before-intake, duplicate and out-of-order state messages cannot reopen tasks.
3. Decision adapter: TL/delegate permission and scope cases, TL self-approval, user denial, version separation, exact request path/body, OBO target audience and real actor attribution, rejection validation, generic bypass prevention. No secret/token logging or persistence.
4. Race tests against OPS: Workflow versus OPS decision, two Workflow decisions, invalidation versus decision; only one decision/history effect, loser receives 409. Test stale figures committing invalidation despite 409, approved-then-invalidated projection, new evaluation isolation and cancellation removing SLA/overdue work.
5. Failure recovery: HTTP timeout before/after OPS commit, Workflow crash after OPS success, callback before/after response, reconciliation outage/recovery and permission revocation. Verify no false completion, duplicate decision, replacement-row approval or service-attributed human decision.
6. Contract tests preserve generic task shapes/error envelope and non-OPS behavior. PostgreSQL integration tests apply Flyway to empty and upgraded schemas and exercise uniqueness/locking; H2 create-drop tests alone do not validate migrations. Coordinated end-to-end evidence must cover both decision entry points and durable recovery.

## 5. Explicit open questions

1. **Identity transport (security/OPS):** confirm OBO feasibility, delegated scopes, consent and confidential-client setup. This design selects OBO as the recommended server caller; if unavailable, agree a browser call directly to OPS using the TL's OPS token followed by authenticated synchronization. Do not implement daemon impersonation as a fallback.
2. **Assignment and visibility (OPS/Workflow owner):** which TL receives the task, how authorized reassignment/claiming works, how current OPS record scope is resolved for Workflow reads, and whether existing delegation cover applies. Current Common Platform directory data does not supply a reliable departmental approver list. Until resolved, fail closed; no broad shared queue assumption.
3. **SLA (product owner):** due-date policy, escalation recipient and how delivery delay affects dueAt. No arbitrary deadline is approved here.
4. **Read/sync contract (OPS owner):** agree proposed endpoint names/payloads, daemon read route/permission/scope, event versions/evidence and retention. Existing GET margin exposes current/history but still requires operations.order.view and normal scope. Establish durable callback plus reconciliation capability before enabling Workflow decisions.
5. **Cancellation compatibility (Workflow/UI owner):** accept additive CANCELLED status and audit representation in all consumers, including the frozen approvals contract, SLA and filters. This requires coordinated implementation; do not disguise cancellation using existing REJECTED semantics.

Validation for this design: source and contract inspection only. NOT verified: OBO provisioning, live permission grants, callback/read endpoints, migrations, race tests or either end-to-end decision path. No runtime tests were run because this task writes only a proposed document. README.md and CLAUDE.md assessed: existing behavior is unchanged; no edits needed. Tracker intentionally unchanged under the explicit single-file scope.
