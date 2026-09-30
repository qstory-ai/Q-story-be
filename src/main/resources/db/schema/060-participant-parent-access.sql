-- 반에서 뺀 아이의 지난 수업 리포트를 학부모가 계속 보게 한다(Q-28).
--
-- 수업 리포트의 열람 권한은 참여 학생의 "지금" 연결된 학부모(tutor_student.linked_parent_user_id)로 정해서,
-- 학부모가 아이를 반에서 빼면(연결 해제) 지난 리포트까지 사라졌다. 연결을 풀 때 그 학생이 참여한 기록마다
-- 학부모를 여기 남겨 두고, 열람 권한은 "지금 연결된 학부모 또는 남겨 둔 학부모"로 본다. 플레이 이용권은
-- 반 소속 기준이라 연결을 풀면 그대로 끊긴다.
alter table public.story_completion_participant
    add column if not exists parent_user_id uuid references public.app_user(id) on delete cascade;
create index if not exists story_completion_participant_parent_idx
    on public.story_completion_participant (parent_user_id)
    where parent_user_id is not null;
