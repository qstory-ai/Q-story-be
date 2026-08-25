-- 상시질문(companion chat): 분기 앵커 밖에서 아이가 캐릭터에게 자유롭게 말 거는 대화 기능.
-- 안전 규칙은 새로 설계하지 않고 라우팅 프롬프트가 이미 120케이스로 검증한 안전망(위험 발화 ->
-- GENTLE_REDIRECT, 안전한 대안 제시해도 route는 바꾸지 않는다)을 그대로 재사용한다 - 그래서
-- route_prompt에 그 규칙 조각을 저장해두고 두 프롬프트가 같은 원문을 공유하게 한다.
alter table if exists public.route_prompt
    add column if not exists companion_safety_fragment text;

update public.route_prompt
set companion_safety_fragment =
    '원래 발화가 위험·폭력·이야기 밖 요청·금지된 미래 정보 요구인 경우 안전한 대안을 제시할 수 있어도 ' ||
    '위험한 내용 자체를 다시 언급하거나 대안으로 재구성하지 않는다. ' ||
    '공포·폭력·위험 행동을 구체적으로 설명하지 않는다.'
where version = 'QSTORY_ROUTE_PROMPT_V6_COVERAGE' and companion_safety_fragment is null;

-- 아이의 원본 발화·전사는 절대 저장하지 않는다(기존 "원본 음성·전사 미저장" 원칙과 동일) -
-- 대화에서 파생된 구조화 태그(관심주제/감정/가치)만 세션-스코프로 남긴다.
create table if not exists public.companion_chat_turn (
    id uuid primary key,
    conversation_id uuid not null,
    story_id varchar not null references public.story(id),
    scene_id varchar not null,
    occurred_at timestamptz not null default now(),
    interaction_mode varchar not null,
    topic_tag varchar,
    tone_tag varchar,
    value_tag varchar
);

create index if not exists companion_chat_turn_conversation_idx
    on public.companion_chat_turn (conversation_id, occurred_at);
create index if not exists companion_chat_turn_occurred_idx
    on public.companion_chat_turn (occurred_at);
