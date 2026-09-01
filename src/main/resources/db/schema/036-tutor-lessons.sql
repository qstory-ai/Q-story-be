-- 방문 선생님의 "수업"(lesson) - IA "[3] 수업 상세"가 요구하는 컨테이너. 학생/이야기/일정을
-- 묶어 예정→진행→완료 상태를 갖는다. 지금까지 tutor_student(학생), tutor_schedule(반복 일정),
-- tutor_lesson_plan(학생에게 담은 이야기), story_completion(완주 기록)만 있었는데, 그것들은
-- 각각 자기 축에서 관리되지만 IA의 "수업"은 그 축들을 실제로 언제 어떤 아이와 무엇을 진행할지
-- 하나로 묶는 상위 레이어다.
create table if not exists public.lesson (
    id uuid primary key,
    tutor_id uuid not null references public.app_user(id) on delete cascade,
    name varchar(80) not null,
    goal text,
    -- IA "수업 일정" - 수업이 예정된 시각. null이면 "일정 미정" 상태로 목록에 남는다.
    scheduled_at timestamptz,
    -- SCHEDULED(예정) / IN_PROGRESS(진행 중) / COMPLETED(완료)
    status varchar(20) not null check (status in ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED')),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index if not exists lesson_tutor_status_idx on public.lesson (tutor_id, status, scheduled_at);

-- 참여 학생 M:N - 한 수업에 여러 학생, 한 학생이 여러 수업. IA "수업 내부 학생 관리" 지원.
create table if not exists public.lesson_student (
    lesson_id uuid not null references public.lesson(id) on delete cascade,
    tutor_student_id uuid not null references public.tutor_student(id) on delete cascade,
    primary key (lesson_id, tutor_student_id)
);

-- 사용 이야기 M:N - 한 수업에 여러 이야기를 순서대로 담을 수 있게. ordinal은 UI에서 순서 표현.
create table if not exists public.lesson_story (
    lesson_id uuid not null references public.lesson(id) on delete cascade,
    story_id varchar(64) not null,
    ordinal int not null default 0,
    primary key (lesson_id, story_id)
);
