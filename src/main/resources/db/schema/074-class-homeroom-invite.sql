-- 반 담임 초대. 반마다 초대가 두 가지다 - 반 초대(학부모, class_group.join_code)와 담임 초대(선생님, 이 표).
-- 선생님이 담임 초대 코드로 가입/로그인해 수락하면 그 기관에 소속되고(organization_tutor) 그 반의 담임이 된다
-- (class_homeroom_history도 같이 남는다). 원장이 기관 소속 선생님을 미리 골라 둘 필요가 없어진다.
--
-- 기관 선생님 초대(organization_tutor_invite)와 같은 형식이다: 긴 무작위 token과 손으로 옮길 수 있는 short_code(8자,
-- JoinCodeGenerator), 만료 14일, 1회용. 다만 원장이 반 화면에서 지금 코드를 다시 볼 수 있어야 해서 token도 원문으로
-- 둔다 - 같은 권한을 가진 short_code가 어차피 원문이라 token만 해시로 두어도 얻는 게 없다.
--
-- 반마다 쓰지 않은 초대는 하나뿐이다. 새로 발급하면 쓰지 않은 이전 초대(만료된 것 포함)를 지우고 새로 넣는다.
-- 쓴 초대는 누가 언제 수락했는지 남기려고 지우지 않는다.
create table if not exists public.class_homeroom_invite (
    id uuid primary key,
    class_group_id uuid not null references public.class_group(id) on delete cascade,
    token varchar(255) not null unique,
    short_code varchar(16) not null unique,
    created_by uuid references public.app_user(id) on delete set null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at timestamptz,
    used_by uuid references public.app_user(id) on delete set null
);

create unique index if not exists class_homeroom_invite_unused_uidx
    on public.class_homeroom_invite (class_group_id)
    where used_at is null;

-- 072/073처럼 백엔드(테이블 소유자 연결)만 읽고 쓴다. Supabase REST로는 보이지 않게 정책 없이 RLS만 켠다.
alter table public.class_homeroom_invite enable row level security;
