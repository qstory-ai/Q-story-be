-- GA4 일일 집계(랜딩 qstory.ai.kr, 웹 demo.qstory.ai.kr, 태블릿 앱)를 우리 DB로 가져온다.
-- analytics/service/GaImportService가 GA Data API(runReport)로 최근 며칠을 매일 다시 받아 날짜 단위로 갈아 끼운다
-- (GA 처리 지연으로 늦게 들어온 데이터를 반영). Grafana "Q-Story 유입 → 전환" 대시보드가 grafana 스키마 뷰로 읽는다.
-- 속성이 여럿이다(랜딩 qstory.ai.kr은 558917997, 웹·앱은 555537619) - property_id로 구분한다.
-- GA가 비워 둔 값은 GA 그대로 '(not set)' 문자열로 남는다 - 기본 키에 null이 들어가지 않게.

-- 1) 이벤트별: 날짜 × 호스트 × 유입(source/medium/campaign) × 플랫폼·화면(사용자 속성) × 이벤트 이름.
create table if not exists public.ga_daily_metrics (
    property_id text not null,
    metric_date date not null,
    host_name text not null,
    session_source text not null,
    session_medium text not null,
    session_campaign text not null,
    qs_platform text not null,
    qs_surface text not null,
    event_name text not null,
    event_count bigint not null,
    total_users bigint not null,
    sessions bigint not null,
    imported_at timestamptz not null default now(),
    primary key (property_id, metric_date, host_name, session_source, session_medium, session_campaign, qs_platform, qs_surface, event_name)
);

-- 2) 방문 규모: 위와 같은 분할에서 이벤트 이름만 뺀 것. 사용자·세션은 이벤트별 행을 더하면 중복으로 세어지므로 따로 받는다.
create table if not exists public.ga_daily_traffic (
    property_id text not null,
    metric_date date not null,
    host_name text not null,
    session_source text not null,
    session_medium text not null,
    session_campaign text not null,
    qs_platform text not null,
    qs_surface text not null,
    total_users bigint not null,
    new_users bigint not null,
    sessions bigint not null,
    engaged_sessions bigint not null,
    page_views bigint not null,
    imported_at timestamptz not null default now(),
    primary key (property_id, metric_date, host_name, session_source, session_medium, session_campaign, qs_platform, qs_surface)
);

-- 3) 가져오기 실행 기록 - 마지막으로 언제 성공했는지, 몇 행을 받았는지(오류 시 사유 한 줄, 키·토큰은 넣지 않는다).
create table if not exists public.ga_import_run (
    id bigserial primary key,
    property_id text not null,
    started_at timestamptz not null,
    finished_at timestamptz not null,
    start_date date not null,
    end_date date not null,
    status varchar(20) not null check (status in ('SUCCESS', 'FAILED')),
    event_rows integer not null default 0,
    traffic_rows integer not null default 0,
    error varchar(500)
);
create index if not exists ga_import_run_started_at_idx on public.ga_import_run (started_at desc);

-- 081과 같이 public 테이블은 정책 없이 RLS를 켜 둔다(백엔드는 소유자로 연결해 우회, Grafana는 아래 뷰만 읽는다).
alter table public.ga_daily_metrics enable row level security;
alter table public.ga_daily_traffic enable row level security;
alter table public.ga_import_run enable row level security;

-- 4) Grafana 뷰. traffic_type: 우리 DB(story_sessions.traffic_type)와 같은 대문자 값 - localhost·사설 IP는 DEV, *.vercel.app 미리보기는 QA, 나머지 BETA.
--    surface: 이벤트가 난 호스트로 랜딩/앱을 나눈다(qs_surface는 사용자 속성이라 랜딩→앱으로 넘어간 사람은 섞인다).
--    channel: 유입 경로를 사람이 읽는 묶음으로. QR 코드 주소는 utm_source=qr&utm_medium=offline.
create or replace view grafana.ga_daily_events as
select
    m.property_id,
    m.metric_date,
    case
        when m.host_name in ('localhost', '127.0.0.1') or m.host_name like '192.168.%' or m.host_name like '10.%' then 'DEV'
        when m.host_name like '%.vercel.app' then 'QA'
        else 'BETA'
    end as traffic_type,
    case
        when m.host_name in ('qstory.ai.kr', 'www.qstory.ai.kr', 'start.qstory.ai.kr') then '랜딩'
        when m.host_name in ('demo.qstory.ai.kr', 'app.qstory.ai.kr', 'play.qstory.ai.kr') or m.qs_surface = 'app' then '앱'
        else '기타'
    end as surface,
    case
        when m.session_source = 'qr' or m.session_medium = 'offline' then 'QR'
        when m.session_source = '(direct)' then '직접'
        when m.session_medium = 'organic' then '검색'
        when m.session_medium in ('cpc', 'ppc', 'paid', 'paidsearch', 'display') then '광고'
        when m.session_medium = 'social' or m.session_source ~* '(instagram|facebook|kakao|band|threads|twitter|youtube|blog\.naver)' then 'SNS'
        when m.session_medium = 'email' then '이메일'
        when m.session_medium = 'referral' then '다른 사이트'
        when m.session_source = '(not set)' then '알 수 없음'
        else '기타'
    end as channel,
    m.host_name,
    m.session_source,
    m.session_medium,
    m.session_campaign,
    m.qs_platform,
    m.qs_surface,
    m.event_name,
    m.event_count,
    m.total_users,
    m.sessions
from public.ga_daily_metrics m;

create or replace view grafana.ga_daily_traffic as
select
    t.property_id,
    t.metric_date,
    case
        when t.host_name in ('localhost', '127.0.0.1') or t.host_name like '192.168.%' or t.host_name like '10.%' then 'DEV'
        when t.host_name like '%.vercel.app' then 'QA'
        else 'BETA'
    end as traffic_type,
    case
        when t.host_name in ('qstory.ai.kr', 'www.qstory.ai.kr', 'start.qstory.ai.kr') then '랜딩'
        when t.host_name in ('demo.qstory.ai.kr', 'app.qstory.ai.kr', 'play.qstory.ai.kr') or t.qs_surface = 'app' then '앱'
        else '기타'
    end as surface,
    case
        when t.session_source = 'qr' or t.session_medium = 'offline' then 'QR'
        when t.session_source = '(direct)' then '직접'
        when t.session_medium = 'organic' then '검색'
        when t.session_medium in ('cpc', 'ppc', 'paid', 'paidsearch', 'display') then '광고'
        when t.session_medium = 'social' or t.session_source ~* '(instagram|facebook|kakao|band|threads|twitter|youtube|blog\.naver)' then 'SNS'
        when t.session_medium = 'email' then '이메일'
        when t.session_medium = 'referral' then '다른 사이트'
        when t.session_source = '(not set)' then '알 수 없음'
        else '기타'
    end as channel,
    t.host_name,
    t.session_source,
    t.session_medium,
    t.session_campaign,
    t.qs_platform,
    t.qs_surface,
    t.total_users,
    t.new_users,
    t.sessions,
    t.engaged_sessions,
    t.page_views
from public.ga_daily_traffic t;

create or replace view grafana.ga_import_runs as
select property_id, started_at, finished_at, start_date, end_date, status, event_rows, traffic_rows, error
from public.ga_import_run;

-- 5) 우리 DB 퍼널(랜딩 방문 → 데모 시작 → 그레텔 대화 → 완주 → 리포트 열기)용. 세션의 유입(utm)과 트래픽 구분을 붙인다.
--    질문 문장 등 metadata는 넣지 않는다.
create or replace view grafana.funnel_events as
select
    e.occurred_at,
    e.event_name,
    e.source,
    e.session_id,
    s.traffic_type,
    s.entry_source,
    s.utm_source,
    s.utm_medium,
    s.utm_campaign,
    e.metadata ->> 'turn_kind' as turn_kind
from public.story_events e
join public.story_sessions s on s.id = e.session_id
where e.event_name in (
    'LANDING_VIEW', 'LANDING_CTA_CLICK', 'STORY_STARTED', 'DIALOGUE_STEP', 'QUESTION_STARTED',
    'STORY_COMPLETED', 'PARENT_REPORT_OPENED', 'REPORT_VIEWED', 'APP_ENTRY', 'SIGNUP_COMPLETED');

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on all tables in schema grafana from anon';
        execute 'revoke all on public.ga_daily_metrics, public.ga_daily_traffic, public.ga_import_run from anon';
        execute 'revoke all on sequence public.ga_import_run_id_seq from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on all tables in schema grafana from authenticated';
        execute 'revoke all on public.ga_daily_metrics, public.ga_daily_traffic, public.ga_import_run from authenticated';
        execute 'revoke all on sequence public.ga_import_run_id_seq from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant select on all tables in schema grafana to grafana_reader';
    end if;
end
$$;
