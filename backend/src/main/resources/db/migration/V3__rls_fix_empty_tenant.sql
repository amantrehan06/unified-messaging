-- Fix: current_setting returns '' (empty string) when the var was set then
-- cleared (e.g. after SET LOCAL expires on commit). ''::uuid throws.
-- NULLIF converts '' to NULL so the cast succeeds and RLS returns zero rows.

drop policy tenant_isolation on tenant_notes;

create policy tenant_isolation on tenant_notes
    using (tenant_id = nullif(current_setting('app.current_tenant', true), '')::uuid);
