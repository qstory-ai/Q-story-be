-- Grafana 대시보드(ops/grafana-alerting/dashboards)가 읽는 집계용 뷰.
-- 원본 테이블 대신 필요한 열만 뽑은 뷰를 따로 둔다 - Grafana 읽기 계정(grafana_reader, 운영 DB에서 수동 생성,
-- ops/grafana-alerting/README.md '대화 품질 대시보드' 참고)에는 이 스키마의 뷰만 읽게 하고 원본 테이블
-- 권한은 주지 않는다. 그래서 metadata 안의 질문 문장(question_text 등)은 Grafana에서 보이지 않는다.
-- public 스키마가 아니라 grafana 스키마에 두는 이유: Supabase는 public 스키마를 REST API로 내보내므로
-- 거기 둔 뷰는 의도치 않게 외부에 열릴 수 있다.
create schema if not exists grafana;

create or replace view grafana.story_events as
select
    e.occurred_at,
    e.event_name,
    e.session_id,
    s.story_id,
    s.traffic_type,
    e.metadata ->> 'anchor_id' as anchor_id,
    e.metadata ->> 'scene_id' as scene_id,
    e.metadata ->> 'route' as route,
    e.metadata ->> 'result' as result,
    e.metadata ->> 'fail_reason' as fail_reason,
    e.metadata ->> 'issue_type' as issue_type,
    e.metadata ->> 'reason_code' as reason_code
from public.story_events e
join public.story_sessions s on s.id = e.session_id;

-- Q-31 그레텔 대화 한 단계(DIALOGUE_STEP). 숫자 값은 형식이 어긋나도 뷰가 깨지지 않게 숫자일 때만 변환한다.
create or replace view grafana.dialogue_steps as
select
    e.occurred_at,
    e.session_id,
    s.story_id,
    s.traffic_type,
    e.metadata ->> 'anchor_id' as anchor_id,
    e.metadata ->> 'scene_id' as scene_id,
    e.metadata ->> 'entry_mode' as entry_mode,
    e.metadata ->> 'turn_kind' as turn_kind,
    e.metadata ->> 'reply_kind' as reply_kind,
    e.metadata ->> 'family_id' as family_id,
    (e.metadata ->> 'via_suggestion') = 'true' as via_suggestion,
    case when e.metadata ->> 'help_step' ~ '^[0-9]+$' then (e.metadata ->> 'help_step')::int end as help_step,
    case when e.metadata ->> 'turn_number' ~ '^[0-9]+$' then (e.metadata ->> 'turn_number')::int end as turn_number,
    case when e.metadata ->> 'elapsed_ms' ~ '^[0-9]+$' then (e.metadata ->> 'elapsed_ms')::bigint end as elapsed_ms
from public.story_events e
join public.story_sessions s on s.id = e.session_id
where e.event_name = 'DIALOGUE_STEP';

-- Supabase에서는 anon/authenticated 역할이 기본 권한으로 새 객체를 읽을 수 있으므로 명시적으로 막는다.
-- (로컬·테스트 Postgres에는 이 역할들이 없으니 있을 때만.)
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on schema grafana from anon';
        execute 'revoke all on all tables in schema grafana from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on schema grafana from authenticated';
        execute 'revoke all on all tables in schema grafana from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant usage on schema grafana to grafana_reader';
        execute 'grant select on all tables in schema grafana to grafana_reader';
    end if;
end
$$;
