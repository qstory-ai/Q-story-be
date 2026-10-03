-- Q-30 헨젤과 그레텔 최종 원고: 행동 분기 없이 대화만 하는 질문 지점(B)과, 질문만 하면 기본 이야기로
-- 이어 가는 질문 지점(A·C)을 위해 기본 분기를 선택 사항으로 바꾼다. 실시간 새 분기 생성도 질문 지점마다
-- 끌 수 있게 한다(꺼진 지점의 기존 LIVE 분기는 지우지 않고 콘텐츠·AI 문맥에서만 뺀다).
alter table public.story_anchor alter column default_fallback_family_id drop not null;
alter table public.story_anchor add column if not exists live_branch_generation boolean not null default true;
