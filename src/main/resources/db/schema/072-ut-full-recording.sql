-- UT 전체 기록. 랜딩의 데모 플레이(로그인 전)도 회차로 남기고, 화면 상호작용과 화면 녹화(rrweb 조각)를 같은 베타 세션
-- id로 묶어 둔다 - 관찰자가 적은 회차 코드 하나로 대화·이벤트·화면 녹화를 함께 찾기 위한 변경.

-- 1) 익명 회차. 로그인하지 않은 데모 플레이는 사용자 대신 베타 세션 id(story_sessions.id와 같은 프런트 생성 값)로
--    주인을 정한다. 베타 세션은 90일 뒤 지워지므로 FK를 걸지 않는다(회차 보관 기간이 더 길다).
alter table public.play_session alter column user_id drop not null;
alter table public.play_session add column if not exists beta_session_id uuid;
create index if not exists play_session_beta_session_idx on public.play_session (beta_session_id);
alter table public.play_session drop constraint if exists play_session_owner_check;
alter table public.play_session add constraint play_session_owner_check
    check (user_id is not null or beta_session_id is not null);

-- 2) 화면 상호작용(화면 진입·이탈, 탭, 스크롤, 머뭇거림). 좌표·스크롤 깊이는 화면 크기 대비 0..1.
--    입력한 글자는 받지 않는다 - target은 요소 식별자(data 속성 등)만 담는다. 90일 뒤 지운다.
create table if not exists public.interaction_event (
    id bigserial primary key,
    beta_session_id uuid not null,
    user_id uuid references public.app_user(id) on delete set null,
    play_session_id uuid,
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    kind varchar(16) not null check (kind in ('SCREEN_VIEW', 'SCREEN_LEAVE', 'TAP', 'SCROLL', 'HESITATION')),
    screen varchar(120),
    target varchar(120),
    target_role varchar(32),
    x real check (x is null or (x >= 0 and x <= 1)),
    y real check (y is null or (y >= 0 and y <= 1)),
    viewport_w integer,
    viewport_h integer,
    scroll_depth real check (scroll_depth is null or (scroll_depth >= 0 and scroll_depth <= 1)),
    duration_ms integer,
    metadata jsonb
);
create index if not exists interaction_event_session_idx on public.interaction_event (beta_session_id, occurred_at);
create index if not exists interaction_event_received_idx on public.interaction_event (received_at);

-- 3) 화면 녹화 조각(rrweb). 프런트가 일정 간격으로 잘라 seq를 붙여 보낸다 - (베타 세션, seq)당 한 번만 들어간다.
--    gzip-base64는 디코드한 gzip 바이트를, json은 UTF-8 바이트를 그대로 둔다. 팀 내부 재생(STAFF)만 읽는다. 90일 뒤 지운다.
create table if not exists public.session_recording_chunk (
    beta_session_id uuid not null,
    seq integer not null check (seq >= 0),
    user_id uuid references public.app_user(id) on delete set null,
    play_session_id uuid,
    started_at timestamptz not null,
    ended_at timestamptz not null,
    event_count integer not null default 0,
    encoding varchar(16) not null check (encoding in ('gzip-base64', 'json')),
    data bytea not null,
    byte_size integer not null,
    received_at timestamptz not null,
    primary key (beta_session_id, seq)
);
create index if not exists session_recording_chunk_received_idx on public.session_recording_chunk (received_at);
create index if not exists session_recording_chunk_play_session_idx on public.session_recording_chunk (play_session_id);

-- 화면 녹화·상호작용은 백엔드(테이블 소유자 연결)만 읽고 쓴다. Supabase REST(anon/authenticated)로는 보이지 않게
-- 정책 없이 RLS만 켠다 - 소유자는 RLS를 우회하므로 앱 동작은 그대로다.
alter table public.interaction_event enable row level security;
alter table public.session_recording_chunk enable row level security;

-- 4) Grafana UT 뷰. ut_sessions는 익명 회차도 보이게 app_user를 left join한다. create or replace view는 기존 열의
--    순서·이름을 바꿀 수 없어서 새 열(beta_session_id, beta_session_code)은 맨 뒤에 붙인다.
create or replace view grafana.ut_sessions as
select
    upper(left(replace(s.id::text, '-', ''), 6)) as session_code,
    s.id as session_id,
    s.user_id,
    s.started_at,
    s.updated_at as last_activity_at,
    u.role as user_role,
    case when ch.birth_year is not null then extract(year from s.started_at)::int - ch.birth_year end as child_age_years,
    ch.age_band as child_age_band,
    case when s.lesson_id is not null then 'LESSON' else 'HOME' end as kind,
    coalesce(s.play_setting, case when s.lesson_id is null then 'HOME' end) as play_setting,
    s.entry_source,
    s.device_platform,
    s.device_browser,
    s.viewport_class,
    s.content_version,
    s.read_from_scene_id,
    s.read_through_scene_id,
    c.end_status,
    c.id as completion_id,
    (select count(*) from public.play_turn t where t.session_id = s.id and t.role = 'CHILD') as child_turns,
    (select count(*) from public.play_turn t where t.session_id = s.id and t.help_step is not null) as help_steps,
    (select count(*) from public.play_turn t where t.session_id = s.id and t.event = 'ACTION_CONFIRMED') as actions,
    (select count(*) from public.play_turn t where t.session_id = s.id and t.event = 'ACTION_CONFIRMED' and t.via_suggestion) as actions_from_example,
    (select count(*) from public.play_turn t where t.session_id = s.id and t.event in ('REPLY_FAILED', 'STT_FAILED')) as failures,
    (select round(avg(t.latency_ms)) from public.play_turn t where t.session_id = s.id and t.latency_ms is not null) as avg_reply_ms,
    (select count(*) from public.story_events e join public.story_sessions bs on bs.id = e.session_id
        where bs.user_id = s.user_id and e.event_name = 'REPORT_VIEWED' and e.metadata ->> 'completion_id' = c.id::text) as report_views,
    s.beta_session_id,
    case when s.beta_session_id is not null then upper(left(replace(s.beta_session_id::text, '-', ''), 6)) end as beta_session_code
from public.play_session s
left join public.app_user u on u.id = s.user_id
left join public.parent_child ch on ch.id = s.child_id
left join public.story_completion c on c.session_id = s.id;

-- 상호작용 뷰. 회차 코드는 회차 안에서 난 상호작용일 때만 있다.
create or replace view grafana.ut_interactions as
select
    upper(left(replace(i.beta_session_id::text, '-', ''), 6)) as beta_session_code,
    case when i.play_session_id is not null then upper(left(replace(i.play_session_id::text, '-', ''), 6)) end as session_code,
    i.occurred_at,
    i.kind,
    i.screen,
    i.target,
    i.target_role,
    i.x,
    i.y,
    i.scroll_depth,
    i.duration_ms,
    i.user_id
from public.interaction_event i;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on all tables in schema grafana from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on all tables in schema grafana from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant select on all tables in schema grafana to grafana_reader';
    end if;
end
$$;
