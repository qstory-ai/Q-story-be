-- 선생님의 학생 등록/일정/부모 초대. class_group/class_invite와 같은 모양이지만 기관이 아닌
-- 개별 TUTOR 계정(app_user.role='TUTOR')이 직접 소유한다.
create table if not exists public.tutor_student (
    id uuid primary key,
    tutor_id uuid not null references public.app_user(id) on delete cascade,
    name varchar(60) not null,
    age_band varchar(20) not null,
    class_type varchar(100),
    prep_note text,
    status varchar(30) not null check (status in ('PENDING_PARENT', 'CONFIRMED')),
    linked_parent_user_id uuid references public.app_user(id) on delete set null,
    created_at timestamptz not null
);

create index if not exists tutor_student_tutor_id_idx on public.tutor_student (tutor_id);

-- 지금은 "매주 반복" 한 종류뿐이다 - 단건 시간 변경/휴강/반복 수정 같은 예외 처리는 다음 단계로 미룬다.
create table if not exists public.tutor_schedule (
    id uuid primary key,
    tutor_student_id uuid not null references public.tutor_student(id) on delete cascade,
    weekday varchar(10) not null check (weekday in ('MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT')),
    start_time time not null,
    end_time time not null,
    start_date date not null,
    location varchar(100) not null,
    reminder_enabled boolean not null default true,
    created_at timestamptz not null
);

create index if not exists tutor_schedule_student_id_idx on public.tutor_schedule (tutor_student_id);

-- class_invite와 동일한 1회용 해시 토큰 패턴.
create table if not exists public.tutor_invite (
    id uuid primary key,
    tutor_student_id uuid not null references public.tutor_student(id) on delete cascade,
    token_hash varchar(255) not null unique,
    method varchar(10) not null check (method in ('SMS', 'LINK')),
    phone_number varchar(30),
    expires_at timestamptz not null,
    used_at timestamptz,
    created_at timestamptz not null
);

create index if not exists tutor_invite_student_id_idx on public.tutor_invite (tutor_student_id);
