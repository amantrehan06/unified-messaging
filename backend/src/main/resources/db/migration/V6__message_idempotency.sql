-- Defense-in-depth: prevent duplicate messages at the database level.
-- The claim CAS prevents double-processing, but this constraint guarantees
-- idempotency even under redelivery, crash-retry, or future Pub/Sub at-least-once push.
-- Nulls are excluded: Postgres unique constraints allow multiple NULLs,
-- so messages without a provider_message_id are not constrained.
alter table messages
  add constraint messages_tenant_provider_msg_uniq
  unique (tenant_id, provider_message_id);
