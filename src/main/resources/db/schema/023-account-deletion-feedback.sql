-- 마이페이지 "회원 탈퇴" 설문 한 건 - app_user를 소프트 삭제(021 참고)하기 직전에 남겨, 왜
-- 떠나는지를 계정이 사라진 뒤에도 계속 알 수 있게 한다. role은 탈퇴 시점 스냅샷이다.
create table if not exists public.account_deletion_feedback (
    id uuid primary key,
    user_id uuid not null references public.app_user (id),
    role varchar(255) not null,
    reason_category varchar(255) not null,
    reason_detail text,
    created_at timestamptz not null
);

create index if not exists account_deletion_feedback_user_id_idx
    on public.account_deletion_feedback (user_id);
