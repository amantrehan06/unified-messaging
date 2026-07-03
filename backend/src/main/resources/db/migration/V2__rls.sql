alter table tenant_notes enable row level security;

create policy tenant_isolation on tenant_notes
    using (tenant_id = current_setting('app.current_tenant', true)::uuid);
