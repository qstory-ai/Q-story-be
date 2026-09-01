-- 방문 선생님이 특정 학생의 다음 수업에 쓸 이야기를 "담아 두는" 리스트. IA "[2] 서재 > 작품
-- 상세 > 수업에 사용하기" 버튼이 여기 한 행을 추가한다. 실제 세션 실행/기록은 여전히
-- story-completion을 통해 별도로 이뤄지므로, 이 테이블은 "무엇을 준비했는지"의 카탈로그
-- 역할만 한다 - 완료 이후 자동으로 사라지진 않고, 선생님이 수업 마무리 후 명시적으로 지운다.
create table if not exists public.tutor_lesson_plan (
    id uuid primary key,
    tutor_id uuid not null references public.app_user(id) on delete cascade,
    tutor_student_id uuid not null references public.tutor_student(id) on delete cascade,
    story_id varchar(64) not null,
    added_at timestamptz not null,
    unique (tutor_student_id, story_id)
);

create index if not exists tutor_lesson_plan_tutor_idx
    on public.tutor_lesson_plan (tutor_id, added_at desc);

create index if not exists tutor_lesson_plan_student_idx
    on public.tutor_lesson_plan (tutor_student_id, added_at desc);
