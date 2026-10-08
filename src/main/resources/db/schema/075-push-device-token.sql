-- 앱 푸시(FCM) 기기 토큰. 앱(Capacitor)이 로그인 뒤 받은 FCM 등록 토큰을 POST /v1/me/push-tokens로 올리면 여기 남고,
-- NotificationPublisher가 인앱 알림을 저장한 뒤(설정·중복 검사 통과) 이 계정의 살아 있는 토큰으로 푸시를 보낸다.
-- token은 기기 하나에 하나라 전역 unique다 - 같은 기기에서 다른 계정으로 로그인하면 행을 새 계정으로 옮긴다.
-- disabled_at: FCM이 UNREGISTERED 등으로 더는 못 쓰는 토큰이라고 답한 시각. 앱이 같은 토큰을 다시 올리면 비운다.
create table if not exists public.push_device_token (
    id uuid primary key,
    user_id uuid not null references public.app_user(id) on delete cascade,
    token varchar(512) not null unique,
    platform varchar(16) not null check (platform in ('ANDROID', 'IOS', 'WEB')),
    created_at timestamptz not null,
    last_seen_at timestamptz not null,
    disabled_at timestamptz
);
create index if not exists push_device_token_user_idx on public.push_device_token (user_id);

-- 072/073처럼 백엔드(테이블 소유자 연결)만 읽고 쓴다. Supabase REST(anon/authenticated)로는 보이지 않게 정책 없이 RLS만 켠다.
alter table public.push_device_token enable row level security;
