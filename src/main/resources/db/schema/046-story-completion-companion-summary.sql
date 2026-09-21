-- 완주 저장 시 companion-chat 대화 요약(태그 집계)을 스냅샷으로 함께 저장한다.
-- 리포트 조회 시점에 조인하지 않고 스냅샷을 뜨는 이유:
--   1) companion_chat_turn은 90일 자동 삭제(CompanionChatRetentionScheduler)이므로,
--      리포트 히스토리는 90일 뒤에 대화 데이터가 사라져 조용히 텅 비게 된다.
--   2) 조회할 때마다 태그 집계를 계산할 이유가 없다 - 완주 시점의 요약은 변하지 않는다.
-- nullable인 이유: 상시 대화를 한 번도 안 한 세션, 익명 데모, 이 컬럼이 없던 시절 기록.
alter table public.story_completion
    add column if not exists companion_chat_summary jsonb;
