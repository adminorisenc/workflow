# Orisenc Workflow Service

Independent Spring Boot 3.5.16 / Java 21 microservice for shared Workflow and Task Management
(`REQ-0022`). It owns the `workflow` PostgreSQL database and exposes the task contract under
`/workflow-service/ws/api/tasks`.

The structure follows the Spring `Sales` service: one Gradle application, the shared Orisenc
security starter, a dedicated PostgreSQL database, an independent port/context path and a public
health endpoint. Unlike the Sales scaffold, this service contains the extracted task domain.

## Local run

Create an empty database named `workflow`, then supply database and Entra values through environment
variables:

- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `ORISENC_TENANT_ID`, `ORISENC_CLIENT_ID`
- `ORISENC_AUTHORIZATION_BASE_URL` when Common Platform is not at its local default

To let the SLA pass actually reach anybody, also point it at the Integration Service:

- `ORISENC_INTEGRATION_CLIENT_ENABLED=true`, `ORISENC_INTEGRATION_BASE_URL`,
  `ORISENC_INTEGRATION_SCOPE`, `ORISENC_INTEGRATION_CLIENT_SECRET`

Without them the client is absent, and the pass records that it could not run rather than stamping
escalations nobody was told about.

Run `./gradlew bootRun`. The defaults are:

- API base: `http://localhost:8087/workflow-service/ws`
- health: `GET /public/health/workflow`
- database: `workflow`
- authorization resolver: `http://localhost:8080/common-platform-service/ws`

## API

| Method | Path | Permission |
| --- | --- | --- |
| `GET` | `/api/tasks`, `/api/tasks/{id}` | `platform.task.view` |
| `POST` | `/api/tasks` | `platform.task.create` |
| `POST` | `/api/tasks/{id}/actions` | `platform.task.view`, then `platform.task.execute` or `platform.task.approve` |
| `POST` | `/api/sla/run` | `platform.work.manage` |
| `GET` | `/api/delegations`, `/api/delegations/{id}` | `platform.delegation.view` |
| `POST` | `/api/delegations` | `platform.delegation.create` |
| `POST` | `/api/delegations/{id}/approve`, `/api/delegations/{id}/reject` | `platform.delegation.approve` |
| `POST` | `/api/delegations/expire-run` | `platform.delegation.manage` |

The permission codes remain unchanged for compatibility with existing Common Platform RBAC grants.
Common Platform owns the permission catalogue; Workflow owns the task records and enforcement.

The work item API lives at `/api/project-tasks` under the separate `platform.work.*` family; see
`MIGRATION_REST_CONTRACTS.md` for its full surface.

Hibernate creates `workflow_task`, `workflow_task_history`, `project_task`, `project_task_history`,
`project_task_comment` and `project_task_checklist_item` in this service's database. Production must
replace `ddl-auto=update` with versioned migrations and `validate` before go-live.

## SLA and escalation

A scheduled pass watches both task families' deadlines and climbs one ladder:

| Rung | When | Who hears |
| --- | --- | --- |
| 1 due soon | within `due-soon-lead` of the deadline | the owner, or the creator when unclaimed |
| 2 breached | the deadline has passed | the owner and the creator |
| 3+ escalated | each `escalation-steps` offset after the deadline | plus the parent item's owner and the configured escalation contact |

Three rules govern it, each configurable under `orisenc.workflow.sla`:

- **Only the highest crossed rung is raised.** A task four days late gets the escalation, not four
  messages - and the first pass over an existing table does not empty a backlog into anyone's inbox.
- **The rung is stored on the item** (`escalation_level`), so a quarter-hourly scan raises a message
  only when the ladder moves upwards. The Integration Service's idempotency on
  `(eventType, eventRef, recipient)` is the second guard, not the first.
- **Nothing is stamped that was not sent.** The pass posts before it writes, so a failed raise leaves
  the item at its old rung for the next pass to retry.

Workflow sends nothing itself. It raises an intention with the Integration Service, which owns
delivery, retries and the log of who was told. The templates it names - `TASK_SLA_DUE_SOON`,
`TASK_SLA_BREACHED`, `TASK_SLA_ESCALATED`, `TASK_ASSIGNED` and `TASK_STATUS_CHANGED` - are seeded
there by `NotificationTemplateRegistrar`.

`escalation-contact` is the honest stand-in for "the owner's manager", which cannot be resolved: no
user record on the platform carries a manager or a department - the same gap that keeps
`RELEVANT_TEAM` visibility coarse.

## Delegation

Delegation widens whose records an already-authorized user may act on; it never grants a permission
of its own and never chains. If A delegates to B and B delegates to C, C cannot act for A. The four
RBAC codes remain owned by Common Platform:

| Permission | Meaning |
| --- | --- |
| `platform.delegation.view` | See delegations concerning you and their event history |
| `platform.delegation.create` | Arrange cover for your own work |
| `platform.delegation.approve` | Approve or reject a privileged delegation requested by somebody else |
| `platform.delegation.manage` | See and administer every delegation, including arranging or revoking one for somebody else |

Scope decides whether cover is privileged; callers do not choose the starting status:

| Scope | Covers | Privileged | Starting status |
| --- | --- | --- | --- |
| `ALL` | Approval tasks and project work items | Yes | `PENDING_APPROVAL` |
| `APPROVALS` | Approval tasks only | Yes | `PENDING_APPROVAL` |
| `WORK_ITEMS` | Project work items only | No | `ACTIVE` |

For privileged scopes, maker-checker is enforced on the entity: the approver can be neither the
delegator nor the delegate, including when an administrator delegates their own approvals. Both
`validFrom` and `validUntil` are required, the start is inclusive, the end exclusive, and
`orisenc.workflow.delegation.max-duration` defaults to 90 days. `ACTIVE` therefore means approved,
not necessarily in force at the current instant; responses expose the server-resolved `inForce`.
Workflow confirms the delegate is an active Common Platform user before creating the record and
fails closed with `503` when that status cannot be resolved.

The hourly expiry pass closes elapsed windows idempotently. Lifecycle notifications are raised only
after commit through the Integration Service (`DELEGATION_ACTIVE` to the delegate;
`DELEGATION_ENDED` to both parties, excluding the actor). History for both task families carries the
real `actor` plus additive `onBehalfOf` when somebody acts as a delegate.
