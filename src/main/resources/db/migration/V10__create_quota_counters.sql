create table quota_counters (
    organization_id uuid not null references organizations(id),
    metric varchar(40) not null,
    period_start date not null,
    used_quantity bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (organization_id, metric, period_start),
    constraint ck_quota_counters_metric check (metric in ('REQUEST')),
    constraint ck_quota_counters_used_quantity_non_negative check (used_quantity >= 0)
);

insert into quota_counters (
    organization_id,
    metric,
    period_start,
    used_quantity,
    created_at,
    updated_at
)
select
    organization_id,
    metric,
    date_trunc('month', occurred_at at time zone 'UTC')::date as period_start,
    sum(quantity) as used_quantity,
    now(),
    now()
from usage_events
group by organization_id, metric, date_trunc('month', occurred_at at time zone 'UTC')::date;
