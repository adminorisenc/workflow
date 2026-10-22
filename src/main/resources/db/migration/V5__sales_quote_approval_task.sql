-- Sales quote approval tasks (owner decisions, 22 Oct 2026): one binding per Sales quote approval round.
-- The approval task itself reuses V4's nullable due date and CANCELLED status.
create table if not exists sales_quote_approval_task (
  request_key varchar(80) primary key,
  quote_id bigint not null,
  approval_round integer not null,
  quote_code varchar(40) not null,
  request_hash varchar(64) not null,
  task_id varchar(40) unique references workflow_task(id),
  source_status varchar(30) not null,
  latest_sequence integer not null,
  decided_by varchar(200),
  decided_at timestamptz,
  reason varchar(2000),
  service_actor varchar(200) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null
);
create unique index if not exists uk_sales_quote_approval_round on sales_quote_approval_task(quote_id, approval_round);
