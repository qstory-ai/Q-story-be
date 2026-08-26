-- STAFF is the internal content-authoring role (see Role.java) - app_user.role's check constraint
-- was written for the three customer-facing roles only and must be widened to admit it. Plain
-- drop-and-readd (not a PL/pgSQL do-block): Spring Boot's script initializer splits schema files on
-- ";" without understanding dollar-quoting, so a do $$ ... $$ block here gets truncated mid-body.
--
-- Includes TUTOR (introduced later by 018-tutor-role.sql) too: every schema file re-runs on every
-- boot with no run-tracking (001-baseline.sql), so once real TUTOR rows exist this file's own
-- drop-and-readd would otherwise reject them for the instant between this statement and 018's -
-- these files describe the current schema, not a frozen history, so this constraint is kept in
-- sync with the full current role set rather than the set that existed when this file was written.
alter table if exists public.app_user drop constraint if exists app_user_role_check;

alter table if exists public.app_user
    add constraint app_user_role_check check (role in ('DIRECTOR', 'CLASS_ACCOUNT', 'PARENT', 'TUTOR', 'STAFF'));
