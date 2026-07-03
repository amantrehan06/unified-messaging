create table channels (
    id                   uuid primary key default gen_random_uuid(),
    tenant_id            uuid not null references tenants(id),
    type                 text not null,
    provider             text not null default 'unipile',
    external_account_id  text,
    status               text not null default 'pending',
    config               jsonb,
    connected_at         timestamptz,
    last_status_at       timestamptz,
    created_at           timestamptz not null default now(),
    unique (tenant_id, type, external_account_id)
);

alter table channels enable row level security;

create policy tenant_isolation on channels
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- Lookup function for webhook handlers that need to find a channel's tenant
-- without already knowing the tenant. Runs as the migration user (table owner)
-- so RLS does not apply.
create function channel_tenant_by_account(p_account_id text)
returns uuid
language sql
security definer
set search_path = public
as $$
    select tenant_id from channels where external_account_id = p_account_id limit 1;
$$;
