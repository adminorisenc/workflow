-- Deduplicate the durable Operations Tech command in the same transaction as its work item.
create table if not exists order_tech_task (
 request_key varchar(100) primary key, order_id varchar(60) not null unique,
 request_hash varchar(64) not null, task_id varchar(120), requested_by varchar(200) not null,
 service_actor varchar(200) not null, created_at timestamptz not null
);
