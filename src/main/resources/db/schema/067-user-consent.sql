-- 약관·개인정보·마케팅·아이 리포트 범위·음성 원본 동의 이력. 동의/철회마다 한 줄씩 쌓고 고치지 않는다.
-- 066은 이미 grafana 뷰가 쓰므로 067.
create table if not exists public.user_consent (
    id uuid primary key,
    user_id uuid not null references public.app_user(id) on delete cascade,
    consent_type varchar(40) not null,
    version varchar(40) not null,
    agreed boolean not null,
    source varchar(40) not null,
    created_at timestamptz not null default now()
);
create index if not exists idx_user_consent_user_type on public.user_consent (user_id, consent_type);

-- 마케팅 알림은 가입 때 받은 동의값으로만 켠다(기본 꺼짐). 이미 저장된 행은 건드리지 않는다.
alter table public.notification_settings alter column marketing_enabled set default false;
