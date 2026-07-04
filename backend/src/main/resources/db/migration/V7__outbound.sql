-- M3: Outbound message sending (reply path)
-- outbound_messages tracks each send attempt with idempotency guard.

create table outbound_messages (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenants(id),
  conversation_id     uuid not null references conversations(id),
  body                text not null,
  idempotency_key     text not null unique,
  provider_message_id text,
  status              text not null default 'queued',
  error               text,
  created_at          timestamptz not null default now(),
  sent_at             timestamptz
);

alter table outbound_messages enable row level security;
create policy tenant_isolation on outbound_messages
  using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
