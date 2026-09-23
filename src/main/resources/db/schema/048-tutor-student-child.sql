-- 선생님이 등록한 학생(tutor_student)과 부모 쪽 아이 프로필(parent_child)을 잇는다.
-- 예전엔 부모가 초대를 수락해도 linked_parent_user_id만 채워지고 아이 행은 만들어지지 않아,
-- 부모 홈에는 아이가 없고 선생님 세션의 완주 기록도 아이에게 이어지지 않았다.
-- TutorStudentService.consumeInvite가 수락 시 기존 아이를 찾거나 새로 만들어 여기에 채운다.
-- 아이 프로필이 삭제되면 링크만 풀린다(학생 행은 남는다).
alter table public.tutor_student
    add column if not exists child_id uuid references public.parent_child(id) on delete set null;

create index if not exists tutor_student_child_id_idx on public.tutor_student (child_id);
