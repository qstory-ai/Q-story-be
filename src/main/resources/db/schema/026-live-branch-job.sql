-- 실시간 새 분기(Live Branch) 생성 작업 큐. 사람 검수 없이 검증-재시도 하네스만으로 아이에게
-- 노출되는 영구 콘텐츠를 만들기 때문에, shadow_family_drafts와 달리 생성 결과 JSON을 여기에
-- 스테이징하지 않는다 - 성공하면 LiveBranchExecutionWorker가 바로 story_action_family/
-- story_fallback_segment/story_asset에 커밋한다(LiveBranchJob.java 참고). 이 테이블은 그 작업의
-- 생명주기(QUEUED -> GENERATING -> READY|FAILED)만 추적한다.
create table if not exists public.live_branch_job (
    id uuid not null,
    story_id varchar(255) not null,
    anchor_id varchar(255) not null,
    child_transcript_redacted varchar(500) not null,
    question_round integer not null,
    status varchar(32) not null check (status in ('QUEUED','GENERATING','READY','FAILED')),
    result_family_id varchar(255),
    error_code varchar(255),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id)
);

-- LiveBranchStaleJobReaper가 QUEUED/GENERATING에 멈춰버린 오래된 job을 찾을 때 쓴다.
create index if not exists live_branch_job_status_updated_at_idx
    on public.live_branch_job (status, updated_at);

-- 재임포트 시 LIVE_GENERATED family를 보호하기 위한 구분(StoryImportService 참고). 기존 행은
-- 전부 저작된(authored) 콘텐츠이므로 add column의 디폴트로 AUTHORED가 자동 백필된다.
alter table public.story_action_family
    add column if not exists origin varchar(32) not null default 'AUTHORED';

-- do $$ 블록은 쓰지 않는다: Spring Boot의 스크립트 초기화는 ";" 기준으로 파일을 나누고
-- dollar-quoting을 이해하지 못해서, 블록 중간에서 잘려버린다(008-staff-role.sql 참고).
alter table if exists public.story_action_family drop constraint if exists story_action_family_origin_check;

alter table if exists public.story_action_family
    add constraint story_action_family_origin_check check (origin in ('AUTHORED', 'LIVE_GENERATED'));
