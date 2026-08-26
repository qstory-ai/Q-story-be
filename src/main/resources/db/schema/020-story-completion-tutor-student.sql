-- 어떤 완주 기록이 방문 선생님과 진행한 수업인지 표시한다 - null이면 가정에서 부모가 자유롭게
-- 본 세션이다. 이 컬럼 하나로 "선생님이 진행한 수업만" 부모에게 공유하는 게 가능해진다.
alter table public.story_completion
    add column if not exists tutor_student_id uuid references public.tutor_student(id) on delete set null;

create index if not exists story_completion_tutor_student_id_idx on public.story_completion (tutor_student_id);
