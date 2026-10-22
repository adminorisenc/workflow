# Sales quote approval tasks

Owner decisions: 22 October 2026. Sales owns approval policy, rounds and decisions; Workflow
projects each round as an unassigned APPROVAL task, department Sales, with no due date.
Numeric Sales quote IDs and string Workflow task IDs are separate identities.

All paths below are relative to `/workflow-service/ws`:

| Method | Path | Authorization |
| --- | --- | --- |
| POST | `/api/sales-quote-approval-tasks` | `platform.quoteapproval.sync`, application token |
| POST | `/api/sales-quote-approval-tasks/{requestKey}/state` | Same |
| GET | `/api/sales-quote-approval-tasks/by-task/{taskId}` | `platform.task.view` and `sales.quote.approve` |

Intake: `{requestKey,quoteId,round,quoteCode,subject,accountName,ownerUserId,requestedBy,
currency,grandTotal,costingGrandTotal,submittedAt}`. `requestKey` is exactly
`sales-quote-{quoteId}-{round}`. One task (`QAP-xxxxxxxx`) per round; identical replay returns
the existing `{taskId,sourceStatus}`; changed payload for the same key returns 409.

State: `{sequence,status,actor,at,reason}`. Status is APPROVED, REJECTED or CANCELLED.
Sales emits sequence 1 for a decision or pending cancellation, sequence 2 for cancellation
after approval. Duplicate/older reports are acknowledged; a conflicting status at the same
sequence returns 409. Missing intake returns 404 and remains retryable in Sales.
APPROVED maps to COMPLETED; REJECTED and CANCELLED retain their task statuses. History keeps
the actual Sales decision actor and the authenticated delivery identity separately.

Binding: `{taskId,quoteId,round,quoteCode,sourceStatus}`. Approvals reads this binding with
the caller's Workflow token, then calls Sales `/api/quotes/{quoteId}/approval/decision`
with that caller's Sales token and the bound round. No claim or generic task action is used.
Sales refuses owner self-approval and stale rounds; Workflow never makes the commercial decision.
Non-approvers receive 404 for source task reads, and generic actions cannot close source tasks.

Sales V13 writes immutable INTAKE/STATE messages in the same transaction as each approval change.
The scheduled delivery locks one due message and never overtakes any undelivered message for
the same quote, across rounds. Failure stores a safe error code, backs off and becomes FAILED
after eight attempts; FAILED blocks later messages for that quote. Operators must investigate
and recover delivery rather than bypassing a failed predecessor. Delivery has no reconciliation
or automatic FAILED-message recovery endpoint in this release.

Workflow V5 creates the binding. Common Platform V16 creates the permissions and
SALES_QUOTE_SYNC role without assigning any principal. Assign that role to Sales' service
principal only after the owner's explicit approval, through Platform → Users. Human tokens
cannot call synchronization even if their permission catalogue includes the sync code.
