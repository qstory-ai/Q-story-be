-- 반 수명주기 - 이름 바꾸기, 지난 반(보관), 학생 반 옮기기, 학기 넘기기(이동·유지·졸업).
--
-- 반은 지우지 않는다. tutor_student·lesson·story_completion의 class_group_id가 반을 지우면 null로 비어(on delete set
-- null) 지난 기록이 어느 반 것인지 잃기 때문이다. "삭제"는 보관(archived_at)이고, 보관된 반은 반 코드·담임 초대로 더
-- 들어올 수 없을 뿐 기록과 명단 이력은 그대로 남는다.

-- 1) 지난 반(보관). archived_at이 있으면 지난 반이다. 원장 목록은 기본으로 지난 반을 빼고 보여 준다.
alter table public.class_group add column if not exists archived_at timestamptz;
alter table public.class_group add column if not exists archived_by uuid references public.app_user(id) on delete set null;

-- 2) 수업 기록의 반 이름 스냅샷. 반 이름을 바꿔도 지난 리포트는 그때 반 이름으로 보인다. 새 기록은 저장할 때
--    채우고(StoryCompletionService), 기존 기록은 지금 반 이름으로 채운다(그때 이름은 남아 있지 않다).
alter table public.story_completion add column if not exists class_name varchar(255);
update public.story_completion c
set class_name = g.name
from public.class_group g
where g.id = c.class_group_id and c.class_name is null;

-- 3) 학생의 반 소속 이력. 원장이 학생을 다른 반으로 옮기면 학생 행(tutor_student)은 그대로 두고 class_group_id만
--    바꾼다 - 학생 행이 같아야 지난 기록(story_completion_participant)과 학부모 연결이 이어진다. 언제부터 언제까지
--    어느 반이었는지는 이 표에 남는다. ended_at이 null인 행이 지금 반이다(학생마다 많아야 하나).
--    reason: 이 구간이 시작된 이유(JOINED 반 코드로 들어옴, MOVED 원장이 옮김, KEPT 학기를 넘기며 같은 반에 남음).
--    end_reason: 끝난 이유(MOVED 다른 반으로 옮김, KEPT 학기를 넘김, GRADUATED 졸업).
--    반은 지우지 않으므로 class_group_id에 cascade를 걸지 않는다. 기관 없는 개인 반만 회원 탈퇴 때 지워지는데, 그
--    전에 그 반의 이력을 먼저 지운다(AccountErasureService).
create table if not exists public.tutor_student_class_history (
    id bigserial primary key,
    tutor_student_id uuid not null references public.tutor_student(id) on delete cascade,
    class_group_id uuid not null references public.class_group(id),
    started_at timestamptz not null,
    ended_at timestamptz,
    reason varchar(20) not null check (reason in ('JOINED', 'MOVED', 'KEPT', 'GRADUATED')),
    end_reason varchar(20) check (end_reason in ('MOVED', 'KEPT', 'GRADUATED')),
    changed_by uuid references public.app_user(id) on delete set null
);
create index if not exists tutor_student_class_history_student_idx
    on public.tutor_student_class_history (tutor_student_id, started_at);
create index if not exists tutor_student_class_history_class_idx
    on public.tutor_student_class_history (class_group_id, ended_at);
create unique index if not exists tutor_student_class_history_open_uidx
    on public.tutor_student_class_history (tutor_student_id)
    where ended_at is null;

-- 지금 반에 있는 학생마다 열린 JOINED 구간 하나 - 언제 들어왔는지는 학생을 등록한 시각으로 둔다.
insert into public.tutor_student_class_history (tutor_student_id, class_group_id, started_at, reason)
select s.id, s.class_group_id, s.created_at, 'JOINED'
from public.tutor_student s
where s.class_group_id is not null
  and not exists (select 1 from public.tutor_student_class_history h where h.tutor_student_id = s.id);

-- 4) 졸업. status(PENDING_PARENT/CONFIRMED)는 학부모 연결 상태라 졸업과 따로 움직인다(학부모가 없는 학생도 졸업한다) -
--    그래서 상태 값을 늘리지 않고 시각 열로 둔다. graduated_at이 있으면 지금 명단·이용권·인원수에서 빠지지만, 행과
--    학부모 연결은 남아 학부모는 지난 수업 리포트를 계속 보고 원장·지난 담임은 지난 반에서 이 학생을 본다.
alter table public.tutor_student add column if not exists graduated_at timestamptz;

-- 072/073/074처럼 백엔드(테이블 소유자 연결)만 읽고 쓴다. Supabase REST로는 보이지 않게 정책 없이 RLS만 켠다.
alter table public.tutor_student_class_history enable row level security;
