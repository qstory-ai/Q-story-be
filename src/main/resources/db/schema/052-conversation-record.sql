-- 아이가 이야기 안에서 말한 모든 것과 캐릭터가 답한 모든 것을 그대로 남기는 append-only 기록.
-- 질문 지점(앵커)의 STT 결과·라우팅 결과, 상시 대화(companion chat)의 STT 결과·답변이 전부
-- 한 테이블에 시간순으로 쌓인다.
--
-- 왜 새 테이블인가: companion_chat_turn은 "원문은 저장하지 않는다"는 전제로 태그만 남기고
-- 90일 뒤 지운다(CompanionChatRetentionScheduler). 그 전제를 뒤집지 않고 그 옆에 따로 두는
-- 편이, 기존 태그 집계(부모 리포트 스냅샷)와 이 원문 기록의 보존 정책을 독립적으로 정할 수 있다.
--
-- 열람 경로는 없다: 이 테이블을 읽는 API·화면은 만들지 않는다(ConversationRecordService는
-- insert와 보존 만료 삭제만 한다). 향후 기능(대화 이어보기, 학습 분석 등)을 만들 때 그 기능이
-- 자기 권한 모델을 갖고 읽어가는 것이 전제다.
--
-- 개인정보: 아이의 발화 원문이 들어간다. 보존 기간은 qstory.conversation-record.retention-days
-- (기본 365일, ConversationRecordRetentionScheduler)로 제한하고, 원본 음성은 여기에도 저장하지
-- 않는다(전사문만). 공개된 개인정보 안내 문구와 보존 기간이 맞는지는 별도로 확인해야 한다.
create table if not exists public.conversation_record (
    id uuid primary key,
    recorded_at timestamptz not null default now(),
    -- QUESTION_TRANSCRIPT / QUESTION_ROUTE / COMPANION_TRANSCRIPT / COMPANION_TURN
    kind varchar(32) not null,
    -- 프론트가 이야기 세션당 하나 만드는 conversationId. 질문·상시대화·완주 기록을 한 세션으로 묶는다.
    session_id uuid,
    story_id varchar(255) not null references public.story(id) on delete cascade,
    scene_id varchar(255) not null,
    anchor_id varchar(255),
    question_round integer,
    -- VOICE(STT를 거친 발화) / TEXT(글로 입력)
    input_mode varchar(16) not null,
    locale varchar(16),
    source_mime_type varchar(64),
    -- 아이가 말하거나 쓴 원문(STT 결과 그대로). 응답 생성에 쓰인 것과 같은 문자열.
    child_text text not null,
    -- 캐릭터의 답(라우팅 응답 또는 상시대화 응답). *_TRANSCRIPT 종류는 아직 답이 없어 null.
    response_text text,
    speaker_id varchar(255),
    -- 질문: DIRECT_ACTION 등 route. 상시대화: ANSWER / GENTLE_REDIRECT.
    route varchar(64),
    action_family_id varchar(255),
    coverage_status varchar(32),
    child_relevant_meaning varchar(500),
    -- 질문 THREE_PATHS 선택지 등 구조화 결과를 그대로 보관(향후 분석용).
    options jsonb,
    topic_tag varchar(255),
    tone_tag varchar(255),
    value_tag varchar(255),
    -- 누가·어떤 아이와 진행한 세션인지. 익명 데모는 전부 null.
    user_id uuid,
    user_role varchar(32),
    child_id uuid references public.parent_child(id) on delete set null,
    tutor_student_id uuid references public.tutor_student(id) on delete set null,
    lesson_id uuid references public.lesson(id) on delete set null,
    -- 재현 가능성: 어떤 모델·프롬프트 버전이 이 답을 냈는지.
    model_id varchar(255),
    prompt_version varchar(255)
);

create index if not exists conversation_record_session_idx
    on public.conversation_record (session_id, recorded_at);
create index if not exists conversation_record_recorded_idx
    on public.conversation_record (recorded_at);
create index if not exists conversation_record_child_idx
    on public.conversation_record (child_id, recorded_at);
create index if not exists conversation_record_tutor_student_idx
    on public.conversation_record (tutor_student_id, recorded_at);
