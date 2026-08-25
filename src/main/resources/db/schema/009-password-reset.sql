-- Password reset tokens (see AuthService.requestPasswordReset/confirmPasswordReset) - same
-- hashed-secret shape as class_invite: only the SHA-256 hash is stored, the raw token is
-- one-time-visible at issue time.
create table if not exists public.password_reset_token (
    id uuid primary key,
    user_id uuid not null references public.app_user on delete cascade,
    token_hash varchar(255) not null unique,
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null default now()
);
