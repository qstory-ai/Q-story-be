-- 마이페이지 "개선사항 요청" - 로그인한 사용자가 남기는 자유 텍스트 피드백 한 건.
create table if not exists public.improvement_feedback (
    id uuid primary key,
    user_id uuid not null references public.app_user (id),
    message text not null,
    created_at timestamptz not null
);

create index if not exists improvement_feedback_user_id_idx
    on public.improvement_feedback (user_id);
