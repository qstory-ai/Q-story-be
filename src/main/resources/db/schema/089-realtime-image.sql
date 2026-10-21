-- 실시간 삽화(Higgsfield GPT Image 2.5 Flare) 기록 - 새 선택지 그림(BRANCH)과 시도 장면 막힘 그림(ATTEMPT).
-- 질문 지점(anchor)·읽기 세션(session)마다 상한(qstory.realtime-image.max-per-anchor-session, 기본 3장) 안에서만
-- 만들고, 자리를 먼저 잡은 뒤(QUEUED) 생성한다. 그림 파일은 Supabase story 이미지 버킷 realtime/<kind>/<id>.<ext>.
create table if not exists public.realtime_image (
    id uuid primary key,
    session_id uuid not null,
    story_id text not null,
    anchor_id text not null,
    kind text not null check (kind in ('BRANCH', 'ATTEMPT')),
    status text not null check (status in ('QUEUED', 'GENERATING', 'READY', 'FAILED')),
    provider_request_id text,
    bucket text,
    object_name text,
    image_url text,
    prompt_hash text,
    error_code text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists realtime_image_budget_idx on public.realtime_image (session_id, anchor_id);

-- 새 선택지 생성 작업이 어느 읽기 세션에서 왔는지 - 실시간 삽화 상한을 세션별로 세기 위해. 구버전 클라이언트는 null.
alter table public.live_branch_job add column if not exists session_id uuid;
