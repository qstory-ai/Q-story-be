-- "반 / 개인 레슨" 선택. 기관의 반(class_group)을 선생님도 소유할 수 있게 열고, 학생과 수업이
-- 반을 가리키게 한다.
--
-- class_group: 예전엔 기관(DIRECTOR)만 만들 수 있었다(organization_id not null). 이제 기관에
-- 소속되지 않은 선생님도 반을 만들 수 있으므로 organization_id를 nullable로 풀고 소유 선생님
-- tutor_id를 둔다. 둘 중 하나는 반드시 있어야 한다(둘 다 있으면 기관 소속 선생님이 기관 안에
-- 만든 반). 기관 반의 부모 가입(join code)·반 계정은 organization_id가 있는 반에서만 동작한다
-- (ClassService 참고).
alter table public.class_group alter column organization_id drop not null;
alter table public.class_group
    add column if not exists tutor_id uuid references public.app_user(id) on delete cascade;
create index if not exists class_group_tutor_id_idx on public.class_group (tutor_id);
alter table public.class_group drop constraint if exists class_group_owner_check;
alter table public.class_group
    add constraint class_group_owner_check check (organization_id is not null or tutor_id is not null);

-- tutor_student: 수업 형태(INDIVIDUAL/CLASS)와 반. CLASS면 class_group_id가 채워진다.
alter table public.tutor_student
    add column if not exists lesson_type varchar(20) not null default 'INDIVIDUAL';
alter table public.tutor_student drop constraint if exists tutor_student_lesson_type_check;
alter table public.tutor_student
    add constraint tutor_student_lesson_type_check check (lesson_type in ('INDIVIDUAL', 'CLASS'));
alter table public.tutor_student
    add column if not exists class_group_id uuid references public.class_group(id) on delete set null;
create index if not exists tutor_student_class_group_id_idx on public.tutor_student (class_group_id);

-- lesson: 반 수업이면 어느 반인지. 참여 학생(lesson_student)은 생성 시 그 반의 학생으로 채워진다.
alter table public.lesson
    add column if not exists class_group_id uuid references public.class_group(id) on delete set null;
create index if not exists lesson_class_group_id_idx on public.lesson (class_group_id);
