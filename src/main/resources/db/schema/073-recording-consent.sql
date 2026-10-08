-- 화면 녹화 동의와 화면 이용 기록 끄기. 화면 녹화는 선택 정보라 서비스 이용 조건이 될 수 없다 - 동의한 경우에만 받는다.
-- 화면 이용 기록(탭·스크롤·머무름)은 기본으로 켜 두고, 로그인한 사용자는 마이페이지에서 끌 수 있다.

-- 1) 화면 녹화 동의. 한 행이 한 주체의 마지막 결정이다 - 베타 세션 단위(user_id 없음) 또는 계정 단위(beta_session_id 없음).
--    source: PROMPT(스토리 시작 전 물음), ACCOUNT(마이페이지 스위치), SIGNUP(가입 때 선택 체크), UT_LINK(UT 링크로 들어와
--    종이 동의서를 받은 방문자), LESSON(선생님이 기관이 보호자 동의를 받았음을 확인한 수업).
create table if not exists public.screen_recording_consent (
    id bigserial primary key,
    beta_session_id uuid,
    user_id uuid references public.app_user(id) on delete cascade,
    granted boolean not null,
    source varchar(16) not null check (source in ('PROMPT', 'ACCOUNT', 'SIGNUP', 'UT_LINK', 'LESSON')),
    decided_at timestamptz not null,
    constraint screen_recording_consent_owner_check check (beta_session_id is not null or user_id is not null)
);
create unique index if not exists screen_recording_consent_beta_session_uidx
    on public.screen_recording_consent (beta_session_id) where user_id is null;
create unique index if not exists screen_recording_consent_user_uidx
    on public.screen_recording_consent (user_id) where beta_session_id is null;

-- 072처럼 백엔드(테이블 소유자 연결)만 읽고 쓴다. Supabase REST(anon/authenticated)로는 보이지 않게 정책 없이 RLS만 켠다.
alter table public.screen_recording_consent enable row level security;

-- 2) 화면 이용 기록 수집 여부(계정 단위). 기본은 켜짐.
alter table public.app_user add column if not exists usage_tracking_enabled boolean not null default true;
