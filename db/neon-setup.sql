-- One-time Neon setup: run in the Neon SQL Editor.
-- Creates the restricted app_user role that the application connects as.
-- RLS applies to this role because it is NOT a superuser and does NOT own the tables.

create role app_user with login password '<REPLACE_WITH_STRONG_PASSWORD>';

grant usage on schema public to app_user;
grant select, insert, update, delete on all tables in schema public to app_user;

-- Ensure future tables created by Flyway (as neondb_owner) are also accessible.
alter default privileges in schema public
    grant select, insert, update, delete on tables to app_user;
