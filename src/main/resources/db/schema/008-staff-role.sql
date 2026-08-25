-- STAFF is the internal content-authoring role (see Role.java) - app_user.role's check constraint
-- was written for the three customer-facing roles only and must be widened to admit it. Plain
-- drop-and-readd (not a PL/pgSQL do-block): Spring Boot's script initializer splits schema files on
-- ";" without understanding dollar-quoting, so a do $$ ... $$ block here gets truncated mid-body.
alter table if exists public.app_user drop constraint if exists app_user_role_check;

alter table if exists public.app_user
    add constraint app_user_role_check check (role in ('DIRECTOR', 'CLASS_ACCOUNT', 'PARENT', 'STAFF'));
