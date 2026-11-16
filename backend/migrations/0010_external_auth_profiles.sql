-- Phase 10 fix: profiles.id no longer requires an auth.users row.
-- Mina authenticates with Firebase (external JWT); profile ids are derived
-- UUIDs (uuid5 from the Firebase UID) that never exist in Supabase auth.users.
-- The FK from 0001_init.sql made every auto-provisioned profile insert fail
-- with ForeignKeyViolationError (profiles_id_fkey), breaking every
-- authenticated request (HTTP 500) including GET /v1/connections.
alter table public.profiles drop constraint if exists profiles_id_fkey;
