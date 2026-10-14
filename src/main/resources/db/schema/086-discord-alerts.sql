-- 디스코드 알림(discord/) 상태. 개인정보는 넣지 않는다 - 경고 이름·날짜·숫자만.

-- 1) 같은 경고는 하루 한 번만: (경고 키, 날짜)를 먼저 넣은 쪽만 보낸다(insert ... on conflict do nothing).
create table if not exists public.discord_alert_log (
    alert_key varchar(100) not null,
    alert_day date not null,
    sent_at timestamptz not null default now(),
    primary key (alert_key, alert_day)
);

-- 2) 정기 묶음 알림의 커서(예: 사용자 반응 30분 묶음이 어디까지 보냈는지) - 재배포로 창을 놓치거나 두 번 보내지 않게.
create table if not exists public.discord_state (
    state_key varchar(100) primary key,
    state_value varchar(200) not null,
    updated_at timestamptz not null default now()
);

-- 3) 외부 API 하루 호출 수(예: Gemini TTS 기본 키, 미국 태평양 날짜 기준) - 재배포해도 하루 사용량이 이어지게.
create table if not exists public.provider_daily_usage (
    provider varchar(40) not null,
    usage_day date not null,
    calls integer not null default 0,
    updated_at timestamptz not null default now(),
    primary key (provider, usage_day)
);

-- 081과 같이 정책 없이 RLS만 켠다(백엔드는 소유자로 연결해 우회한다).
alter table public.discord_alert_log enable row level security;
alter table public.discord_state enable row level security;
alter table public.provider_daily_usage enable row level security;
do $$
declare
    r text;
begin
    foreach r in array array['anon', 'authenticated'] loop
        if exists (select 1 from pg_roles where rolname = r) then
            execute format('revoke all on public.discord_alert_log from %I', r);
            execute format('revoke all on public.discord_state from %I', r);
            execute format('revoke all on public.provider_daily_usage from %I', r);
        end if;
    end loop;
end
$$;
