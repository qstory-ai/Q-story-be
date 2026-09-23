-- 완주 기록을 수업(lesson)에 연결한다. 수업 상세에서 "시작"한 세션은 lesson_id를 싣고 오며,
-- 서비스가 그 수업의 참여 학생 한 명마다 완주 기록을 하나씩 남긴다(예전엔 첫 학생 한 명에게만
-- 기록됐다 - TutorLessonDetailPage가 students[0]만 tutorStudentId로 넘겼다).
-- 수업이 삭제돼도 기록은 남겨야 하므로 set null.
alter table public.story_completion
    add column if not exists lesson_id uuid references public.lesson(id) on delete set null;

create index if not exists story_completion_lesson_idx
    on public.story_completion (lesson_id, completed_at desc);
