-- grafana.ut_turns에서 아이 말 원문(play_turn.text)을 뺀다(보안 점검 M6). Grafana 읽기 계정과 대시보드 열람자에게 아이 발화
-- 원문이 그대로 보였다 - 원문은 보호자 리포트(앱 권한 검사를 거친다)에서만 본다. 대신 글자 수(text_length)를 둔다.
-- 열을 빼는 변경이라 create or replace로는 안 되고 뷰를 다시 만든다(이 뷰를 읽는 다른 뷰는 없다).
-- 대시보드 쿼리(ops/grafana-alerting/dashboards.tf "회차 대화")도 text 대신 text_length를 읽게 함께 바꿨다.
drop view if exists grafana.ut_turns;
create view grafana.ut_turns as
select
    upper(left(replace(t.session_id::text, '-', ''), 6)) as session_code,
    t.seq,
    t.occurred_at,
    t.scene_id,
    t.anchor_id,
    t.entry_mode,
    t.role,
    t.speaker,
    char_length(t.text) as text_length,
    t.input_mode,
    t.transcript_edited,
    t.help_step,
    t.reply_kind,
    t.latency_ms,
    t.event,
    t.family_id,
    t.via_suggestion,
    t.error_code
from public.play_turn t;

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
