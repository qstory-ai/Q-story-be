-- 로그인하지 않은 데모 회차의 리포트 분석(10/25). 지금까지 분석(report_analysis)은 계정에 저장된 기록(story_completion)에만
-- 만들어 데모 리포트는 ④ 관심과 생각·⑤ 대화 카드가 비어 있었다. 데모 회차(play_session, user_id 없음)를 기준으로 같은 분석을
-- 만들어 둔다. 회차가 지워지면(보관 기간·탈퇴) 같이 지워진다.
create table if not exists public.demo_report_analysis (
    session_id uuid primary key references public.play_session (id) on delete cascade,
    story_id varchar(64) not null,
    read_from_scene_id varchar(64),
    read_through_scene_id varchar(64),
    status varchar(16) not null,
    attempts integer not null default 0,
    model_id varchar(128),
    prompt_version varchar(64),
    result jsonb,
    error varchar(500),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists demo_report_analysis_status_idx on public.demo_report_analysis (status, updated_at);

alter table public.demo_report_analysis enable row level security;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on public.demo_report_analysis from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on public.demo_report_analysis from authenticated';
    end if;
end
$$;
