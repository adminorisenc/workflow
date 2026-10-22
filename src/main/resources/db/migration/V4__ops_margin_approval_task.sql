-- OPS margin approval tasks (Workflow/docs/OPS_MARGIN_APPROVAL_TASK_CONTRACT.md, owner decisions 22 Oct 2026):
-- an approval with no due date, a CANCELLED status for margins OPS invalidated, and one binding per OPS margin.
alter table workflow_task alter column due_at drop not null;
alter table workflow_task drop constraint if exists workflow_task_status_check;
alter table workflow_task add constraint workflow_task_status_check
  check (status in ('PENDING','IN_PROGRESS','COMPLETED','REJECTED','CANCELLED'));
create table if not exists ops_margin_approval_task (
  margin_id bigint primary key,
  request_key varchar(60) not null unique,
  order_id bigint not null,
  revision_id bigint not null,
  margin_version bigint not null,
  order_number varchar(60) not null,
  request_hash varchar(64) not null,
  task_id varchar(40) unique references workflow_task(id),
  source_status varchar(30) not null,
  latest_ops_version bigint not null,
  decided_by varchar(200),
  decided_at timestamptz,
  reason varchar(2000),
  service_actor varchar(200) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);
