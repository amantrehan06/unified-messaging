create table tenants (
    id         uuid primary key default gen_random_uuid(),
    name       text not null,
    vertical   text,
    status     text not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table users (
    id               uuid primary key default gen_random_uuid(),
    tenant_id        uuid not null references tenants(id),
    email            text not null unique,
    auth_provider    text not null,
    external_auth_id text not null,
    role             text not null default 'owner',
    created_at       timestamptz not null default now(),
    unique (auth_provider, external_auth_id)
);

create table tenant_notes (
    id         uuid primary key default gen_random_uuid(),
    tenant_id  uuid not null references tenants(id),
    body       text not null,
    created_at timestamptz not null default now()
);
