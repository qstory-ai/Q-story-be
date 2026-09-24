-- 선생님·학생·수업 정합성 보강(2026-09-24 검토 결과).
--
-- 1) tutor_student 소프트 삭제: 학생을 hard delete하면 story_completion.tutor_student_id가 null이 되어
--    부모가 그 학생의 선생님 리포트에 접근할 경로(학생↔linked_parent_user 조인)를 전부 잃고, 알림
--    링크도 404가 됐다. 이제 deleted_at만 채우고 행은 남긴다 - 모든 조회는 deleted_at is null로 거른다.
alter table public.tutor_student add column if not exists deleted_at timestamptz;
create index if not exists tutor_student_tutor_deleted_idx on public.tutor_student (tutor_id, deleted_at);

-- 2) 한 선생님이 같은 아이 프로필을 두 번 연결하지 못하게: 두 학생 등록이 같은 child_id를 가리키면
--    반 수업 한 번에 그 아이의 완주 기록이 두 번 남는다. 삭제된 학생 행은 제외.
create unique index if not exists tutor_student_tutor_child_uidx
    on public.tutor_student (tutor_id, child_id)
    where child_id is not null and deleted_at is null;

-- 3) 완주 저장 멱등성: 프론트가 이야기 세션당 하나 만드는 conversationId(session_id)를 저장해,
--    재시도로 record()가 두 번 와도 같은 세션의 기록이 두 번 남거나 부모 알림이 두 번 가지 않게 한다.
--    반 수업은 참여 학생마다 한 행이므로 (session_id, tutor_student_id) 단위, 가정 세션은 session_id 단위.
alter table public.story_completion add column if not exists session_id uuid;
create unique index if not exists story_completion_session_solo_uidx
    on public.story_completion (session_id)
    where session_id is not null and tutor_student_id is null;
create unique index if not exists story_completion_session_student_uidx
    on public.story_completion (session_id, tutor_student_id)
    where session_id is not null and tutor_student_id is not null;
