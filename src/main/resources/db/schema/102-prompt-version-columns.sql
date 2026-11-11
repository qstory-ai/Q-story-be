-- 새 선택지 작업과 실시간 삽화가 어느 판의 프롬프트로 만들어졌는지 남긴다 - 프롬프트 글을 고친 뒤 결과가 좋아졌는지
-- 판끼리 비교하려면 행마다 버전이 있어야 한다. 예전 행은 버전을 알 수 없어 null로 둔다.
alter table public.live_branch_job add column if not exists prompt_version varchar(64);
alter table public.realtime_image add column if not exists prompt_version varchar(64);
