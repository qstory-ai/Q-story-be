-- Q-40 UT 데이터 수집. Q-40 문서 6-3의 구간(웹사이트 진입·가입·반 연결·사용 조건·동화 읽기·질문 초대·캐릭터 대화·
-- 이야기 변화·리포트)을 앱 기록으로 남기고, 현장 관찰·인터뷰를 같은 회차에 이어 붙이기 위한 변경.

-- 1) 통계 세션 ↔ 계정. 로그인한 채 보낸 이벤트면 그 계정을 남긴다 - 방문·가입·플레이·리포트를 참여자 한 명의 흐름으로 잇는다.
alter table public.story_sessions add column if not exists user_id uuid references public.app_user(id) on delete set null;
create index if not exists story_sessions_user_idx on public.story_sessions (user_id);

-- 2) 앱 화면(플레이어 밖) 이벤트 출처와 새 이벤트 이름.
alter table public.story_events drop constraint if exists story_events_source_check;
alter table public.story_events add constraint story_events_source_check check (source in ('LANDING', 'PLAYER', 'APP'));
alter table public.story_events drop constraint if exists story_events_event_name_check;
alter table public.story_events add constraint story_events_event_name_check check (event_name in (
    'LANDING_VIEW','LANDING_CTA_CLICK','STORY_STARTED','SCENE_REACHED','QUESTION_INVITE_SHOWN','QUESTION_SKIPPED',
    'QUESTION_STARTED','CHOICE_SELECTED','QUESTION_RESULT','PLAYBACK_ISSUE','EXPLICIT_EXIT','STORY_COMPLETED',
    'PARENT_REPORT_OPENED','SURVEY_OPENED','DIALOGUE_STEP',
    'APP_ENTRY','SIGNUP_STARTED','SIGNUP_COMPLETED','CHILD_REGISTERED','CLASS_JOIN','CONSENT_SAVED',
    'PLAYBACK_CONTROL','REPORT_VIEWED','REPORT_ACTION'));

-- 3) 회차의 사용 조건(진입 경로·진행 형태·기기)과 대화 줄의 답 시간·오류.
alter table public.play_session add column if not exists entry_source varchar(32);
alter table public.play_session add column if not exists play_setting varchar(16);
alter table public.play_session drop constraint if exists play_session_play_setting_check;
alter table public.play_session add constraint play_session_play_setting_check
    check (play_setting is null or play_setting in ('HOME', 'INDIVIDUAL', 'SMALL_GROUP', 'WHOLE_CLASS'));
alter table public.play_session add column if not exists device_platform varchar(32);
alter table public.play_session add column if not exists device_browser varchar(32);
alter table public.play_session add column if not exists viewport_class varchar(32);

alter table public.play_turn add column if not exists latency_ms integer;
alter table public.play_turn add column if not exists error_code varchar(60);
alter table public.play_turn drop constraint if exists play_turn_event_check;
alter table public.play_turn add constraint play_turn_event_check check (event is null or event in (
    'ACTION_CONFIRMED', 'ACTION_DECLINED', 'INVITE_SKIPPED', 'INVITE_CLOSED', 'REPLY_FAILED', 'STT_FAILED'));

-- 4) UT 분석 뷰(Grafana "Q-Story UT 회차"). 회차 코드 = 회차 id 앞 6자(대문자) - 관찰자가 화면에서 보고 적는 값.
--    UT 분석은 팀 내부용이라 아이 말 원문을 포함한다(보호자 리포트와 같은 기록). 통계 이벤트의 질문 원문 키는 넣지 않는다.
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
        where bs.user_id = s.user_id and e.event_name = 'REPORT_VIEWED' and e.metadata ->> 'completion_id' = c.id::text) as report_views
from public.play_session s
join public.app_user u on u.id = s.user_id
left join public.parent_child ch on ch.id = s.child_id
left join public.story_completion c on c.session_id = s.id;

create or replace view grafana.ut_turns as
select
    upper(left(replace(t.session_id::text, '-', ''), 6)) as session_code,
    t.seq,
    t.occurred_at,
    t.scene_id,
    t.anchor_id,
    t.entry_mode,
    t.role,
    t.speaker,
    t.text,
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

create or replace view grafana.ut_events as
select
    e.occurred_at,
    e.event_name,
    e.source,
    s.user_id,
    u.role as user_role,
    s.traffic_type,
    e.metadata - 'question_text' - 'question_intent' as metadata
from public.story_events e
join public.story_sessions s on s.id = e.session_id
left join public.app_user u on u.id = s.user_id;

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
