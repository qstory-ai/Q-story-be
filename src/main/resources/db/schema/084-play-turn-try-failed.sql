-- 그레텔 대화 "시도 장면"(PM 피드백 → CTO 결정: 다치거나 남을 해치는 행동만 막고, 그 밖에 준비되지 않은 행동은 이야기 속에서
-- 해 보다가 막히는 짧은 장면을 보여 준 뒤 그 장면에서 몇 번 더 이야기하고 원래 질문 지점으로 돌아온다).
-- 회차 기록(play_turn)에 시작(TRY_FAILED, text = 해 본 것)과 마침(TRY_ENDED, attempt_turns = 장면 안 아이 말 수)을 남긴다.
-- 장면 줄은 reply_kind=ATTEMPT_SCENE, 그레텔 답은 TRY_FAIL/ATTEMPT_END(varchar(16), 값 제약 없음)로 남는다.
-- 대화 원장(conversation_record)은 route=TRY_AND_FAIL / TRY_AND_FAIL_TALK_n / TRY_AND_FAIL_END_n.
alter table public.play_turn add column if not exists attempt_turns smallint;
alter table public.play_turn drop constraint if exists play_turn_event_check;
alter table public.play_turn add constraint play_turn_event_check check (event is null or event in (
    'ACTION_CONFIRMED', 'ACTION_DECLINED', 'INVITE_SKIPPED', 'INVITE_CLOSED', 'REPLY_FAILED', 'STT_FAILED',
    'TRY_FAILED', 'TRY_ENDED'));
