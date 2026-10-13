-- 그레텔 대화 "실패해 보기"(PM 피드백 - 너무 비교육적인 것만 아니면 거절하지 말고 해 보고 실패하는 모습을 보여 준다).
-- 질문 초대에서 아이가 몰래 가져가기·거짓말·혼자 도망치기 같은 행동을 내면 그레텔이 짧은 실패 장면을 들려주고 같은 질문
-- 지점으로 돌아온다. 그 사실을 회차 기록(play_turn)에 SYSTEM 줄 event=TRY_FAILED로 남길 수 있게 허용 값을 넓힌다.
-- 그레텔 답 줄은 reply_kind=TRY_FAIL(varchar(16), 값 제약 없음), 대화 원장(conversation_record)은 route=TRY_AND_FAIL로 남는다.
alter table public.play_turn drop constraint if exists play_turn_event_check;
alter table public.play_turn add constraint play_turn_event_check check (event is null or event in (
    'ACTION_CONFIRMED', 'ACTION_DECLINED', 'INVITE_SKIPPED', 'INVITE_CLOSED', 'REPLY_FAILED', 'STT_FAILED',
    'TRY_FAILED'));
