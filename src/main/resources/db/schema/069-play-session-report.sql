-- Q-39 부모/기관 리포트 기본정립.
--
-- 1) play_session · play_turn: 회차와 그 안의 대화 한 줄 한 줄. 지금까지는 완주한 회차의 질문 지점별 요약
--    (story_completion.outcomes)만 남고 원문은 읽을 수 없는 대화 원장(conversation_record)에만 있었다. 이제
--    부모 리포트가 아이 말 원문과 앞뒤 대화를 그대로 보여 주므로, 리포트가 읽는 기록을 따로 둔다.
--    session id는 프런트의 conversationId와 같다(story_completion.session_id와 조인된다).
--    원문 보관 기간은 대화 원장과 같다(qstory.conversation-record.retention-days, 기본 365일) -
--    PlayTurnRetentionScheduler가 지운다. 지워진 회차의 리포트는 요약(outcomes)으로만 보인다.
-- 2) story_completion: 작품 버전·읽은 범위·종료 상태(완주/중도 종료)·교사 메모 두 칸(나만 보기/부모 공유).
-- 3) report_analysis: 회차가 끝난 뒤 만든 관심·생각 관찰과 대화 카드. 근거 대화(seq)를 함께 저장한다.

create table if not exists public.play_session (
    id uuid primary key,
    user_id uuid not null references public.app_user(id) on delete cascade,
    story_id varchar(64) not null,
    content_version varchar(80),
    child_id uuid references public.parent_child(id) on delete set null,
    tutor_student_id uuid references public.tutor_student(id) on delete set null,
    lesson_id uuid references public.lesson(id) on delete set null,
    read_from_scene_id varchar(64),
    read_through_scene_id varchar(64),
    started_at timestamptz not null,
    updated_at timestamptz not null
);
create index if not exists play_session_user_idx on public.play_session (user_id, started_at desc);

create table if not exists public.play_turn (
    session_id uuid not null references public.play_session(id) on delete cascade,
    seq integer not null check (seq > 0),
    occurred_at timestamptz not null,
    received_at timestamptz not null,
    scene_id varchar(64) not null,
    visual_id varchar(80),
    anchor_id varchar(64),
    entry_mode varchar(16) check (entry_mode in ('SPONTANEOUS', 'INVITE', 'HELP')),
    role varchar(16) not null check (role in ('CHILD', 'CHARACTER', 'SYSTEM')),
    -- 아이 말을 누가 입력했는지 - 앱이 자동으로 판별하지 않는다. 기본 UNVERIFIED.
    speaker varchar(20) check (speaker in ('UNVERIFIED', 'GUARDIAN_PROXY', 'TEACHER_RELAY')),
    character_speaker_id varchar(64),
    text varchar(500),
    input_mode varchar(8) check (input_mode in ('VOICE', 'TEXT')),
    transcript_edited boolean,
    fixed boolean,
    help_step integer,
    reply_kind varchar(16),
    proposed_family_id varchar(80),
    reply_audio_played boolean,
    event varchar(24) check (event in ('ACTION_CONFIRMED', 'ACTION_DECLINED', 'INVITE_SKIPPED', 'INVITE_CLOSED')),
    family_id varchar(80),
    via_suggestion boolean,
    suggestion_label varchar(80),
    result_visual_id varchar(80),
    primary key (session_id, seq)
);
create index if not exists play_turn_received_idx on public.play_turn (received_at);

alter table public.story_completion add column if not exists content_version varchar(80);
alter table public.story_completion add column if not exists end_status varchar(16) not null default 'COMPLETED';
alter table public.story_completion drop constraint if exists story_completion_end_status_check;
alter table public.story_completion add constraint story_completion_end_status_check
    check (end_status in ('COMPLETED', 'EXITED'));
alter table public.story_completion add column if not exists read_from_scene_id varchar(64);
alter table public.story_completion add column if not exists read_through_scene_id varchar(64);
alter table public.story_completion add column if not exists teacher_note_internal varchar(1000);
alter table public.story_completion add column if not exists teacher_note_for_parents varchar(1000);

create table if not exists public.report_analysis (
    completion_id uuid primary key references public.story_completion(id) on delete cascade,
    status varchar(16) not null check (status in ('PENDING', 'RUNNING', 'READY', 'FAILED', 'SKIPPED')),
    model_id varchar(120),
    prompt_version varchar(40),
    result jsonb,
    error varchar(500),
    attempts integer not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index if not exists report_analysis_pending_idx on public.report_analysis (status, updated_at);
