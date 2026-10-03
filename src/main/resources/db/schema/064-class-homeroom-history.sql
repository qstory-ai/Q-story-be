-- 반 담임 변경과 담임 이력(Q-35).
--
-- 지금까지는 담임이 있는 기관 반의 담임을 바꿀 수 없었다(수업·기록이 선생님에게 묶여 있어서). 정책:
-- 지난 수업과 리포트는 그때 진행한 선생님 것으로 남고(lesson.tutor_id, story_completion.user_id는 그대로),
-- 바꾼 뒤의 수업만 새 담임에게 간다. 관리자는 두 선생님의 기록을 모두 본다. 반마다 언제부터 언제까지 누가
-- 담임이었는지를 이 표에 남긴다 - ended_at이 null인 행이 지금 담임이다.
create table if not exists public.class_homeroom_history (
    id uuid primary key default gen_random_uuid(),
    class_group_id uuid not null references public.class_group(id) on delete cascade,
    tutor_id uuid not null references public.app_user(id) on delete cascade,
    started_at timestamptz not null,
    ended_at timestamptz
);
create index if not exists class_homeroom_history_class_idx
    on public.class_homeroom_history (class_group_id, started_at);
-- 반마다 지금 담임은 한 명뿐이다.
create unique index if not exists class_homeroom_history_current_uidx
    on public.class_homeroom_history (class_group_id)
    where ended_at is null;

-- 이미 담임이 있는 반은 반을 만든 시각부터 지금 담임이었던 것으로 채운다(정확한 배정 시각은 남아 있지 않다).
insert into public.class_homeroom_history (class_group_id, tutor_id, started_at)
select c.id, c.tutor_id, c.created_at
from public.class_group c
where c.tutor_id is not null
  and not exists (select 1 from public.class_homeroom_history h where h.class_group_id = c.id and h.ended_at is null);
