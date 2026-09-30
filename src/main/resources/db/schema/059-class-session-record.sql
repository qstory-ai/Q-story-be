-- 반 수업 기록을 반 단위 한 건으로(Q-28).
--
-- 지금까지 반 수업 한 번은 참여 학생 수만큼 story_completion을 복제했다(050). 단체 발화와 대화 요약이
-- 아이마다 "우리 아이 기록"으로 남아 부모에게 개별 리포트처럼 보였고, 아이별 가정 기록에도 섞였다.
-- 이제 반 수업은 한 행(group_session = true, tutor_student_id·child_id 없음)과 참여 학생 목록
-- (story_completion_participant)으로 남긴다. 부모의 열람 권한은 참여 학생의 연결된 부모로 정한다.

create table if not exists public.story_completion_participant (
    completion_id uuid not null references public.story_completion(id) on delete cascade,
    tutor_student_id uuid not null references public.tutor_student(id) on delete cascade,
    primary key (completion_id, tutor_student_id)
);
create index if not exists story_completion_participant_student_idx
    on public.story_completion_participant (tutor_student_id);

alter table public.story_completion add column if not exists group_session boolean not null default false;

-- 1) 기존 선생님 세션은 그 행의 학생이 참여자다.
insert into public.story_completion_participant (completion_id, tutor_student_id)
select id, tutor_student_id from public.story_completion where tutor_student_id is not null
on conflict do nothing;

-- 2) 한 번의 기록 요청으로 복제된 행(같은 수업·같은 선생님·같은 완료 시각)을 가장 먼저 만든 행 하나로 모은다.
with grouped as (
    select id,
           first_value(id) over w as keep_id,
           count(*) over (partition by lesson_id, user_id, completed_at) as copies
    from public.story_completion
    where lesson_id is not null and tutor_student_id is not null
    window w as (partition by lesson_id, user_id, completed_at order by created_at, id)
)
insert into public.story_completion_participant (completion_id, tutor_student_id)
select g.keep_id, p.tutor_student_id
from grouped g
join public.story_completion_participant p on p.completion_id = g.id
where g.copies > 1
on conflict do nothing;

-- 알림 링크가 지워질 행을 가리키면 남는 행으로 돌린다.
with grouped as (
    select id,
           first_value(id) over w as keep_id,
           count(*) over (partition by lesson_id, user_id, completed_at) as copies
    from public.story_completion
    where lesson_id is not null and tutor_student_id is not null
    window w as (partition by lesson_id, user_id, completed_at order by created_at, id)
)
update public.notifications n
set href = '/reports/' || g.keep_id
from grouped g
where g.copies > 1 and g.id <> g.keep_id and n.href = '/reports/' || g.id;

with grouped as (
    select id,
           first_value(id) over w as keep_id,
           count(*) over (partition by lesson_id, user_id, completed_at) as copies
    from public.story_completion
    where lesson_id is not null and tutor_student_id is not null
    window w as (partition by lesson_id, user_id, completed_at order by created_at, id)
)
delete from public.story_completion c
using grouped g
where c.id = g.id and g.copies > 1 and g.id <> g.keep_id;

-- 3) 반을 고른 수업이거나 참여 학생이 여럿인 기록은 반 수업이다 - 특정 아이의 기록이 아니다.
update public.story_completion c
set group_session = true, tutor_student_id = null, child_id = null
where c.lesson_id is not null
  and (exists (select 1 from public.lesson l where l.id = c.lesson_id and l.class_group_id is not null)
       or (select count(*) from public.story_completion_participant p where p.completion_id = c.id) > 1);
