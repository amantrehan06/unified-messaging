-- M2b: Ingestion pipeline tables
-- inbound_events is the durable ledger (write-first, before any compute).
-- contacts, conversations, messages are tenant-scoped with RLS.

create table inbound_events (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid,
  provider          text not null default 'unipile',
  event_type        text,
  raw_payload       jsonb not null,
  dedupe_key        text not null unique,
  processing_status text not null default 'received',
  retry_count       int  not null default 0,
  claimed_at        timestamptz,
  last_error        text,
  received_at       timestamptz not null default now(),
  processed_at      timestamptz
);
create index on inbound_events (processing_status, received_at);

create table contacts (
  id                uuid primary key default gen_random_uuid(),
  tenant_id         uuid not null references tenants(id),
  channel_type      text not null,
  external_identity text not null,
  display_name      text,
  created_at        timestamptz not null default now(),
  unique (tenant_id, channel_type, external_identity)
);

create table conversations (
  id              uuid primary key default gen_random_uuid(),
  tenant_id       uuid not null references tenants(id),
  contact_id      uuid not null references contacts(id),
  channel_id      uuid references channels(id),
  state           text not null default 'AI_HANDLING',
  state_reason    text,
  last_message_at timestamptz,
  created_at      timestamptz not null default now()
);
create index on conversations (tenant_id, state, last_message_at);

create table messages (
  id                  uuid primary key default gen_random_uuid(),
  tenant_id           uuid not null references tenants(id),
  conversation_id     uuid not null references conversations(id),
  direction           text not null,
  author              text not null,
  body                text,
  provider_message_id text,
  provider_timestamp  timestamptz not null,
  status              text not null default 'received',
  created_at          timestamptz not null default now()
);
create index on messages (tenant_id, conversation_id, provider_timestamp);

-- RLS on tenant-owned tables (reuse M1 fail-closed pattern)
alter table contacts      enable row level security;
alter table conversations enable row level security;
alter table messages      enable row level security;

create policy tenant_isolation on contacts
  using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
create policy tenant_isolation on conversations
  using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
create policy tenant_isolation on messages
  using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);

-- NOTE: inbound_events is NOT under RLS. It is written pre-tenant-resolution
-- (tenant_id nullable). Access it via restricted app code paths only.
