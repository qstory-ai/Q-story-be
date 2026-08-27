-- Phase 2 §3: 한 live_branch_job이 family 하나가 아니라 최대 3개(새로 생성 + 기존으로 채운 자리)를
-- 만든다 - 그래서 단일 result_family_id 대신 정확히 3개(성공했다면)의 {familyId, label, meaning}을
-- 담는 result_options_json으로 바뀐다(LiveBranchJob.java/LiveBranchController 참고). Phase 1이
-- 아직 실제 사용자에게 배포되지 않은 상태라 데이터 마이그레이션 없이 컬럼을 교체한다.
alter table public.live_branch_job
    add column if not exists result_options_json jsonb;

alter table public.live_branch_job
    drop column if exists result_family_id;
