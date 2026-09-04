-- 부모 계정(app_user.role='PARENT')이 등록한 아이 프로필. tutor_student가 선생님의 학생을
-- 담듯이, 여기는 부모 본인의 아이를 담는다 - 한 부모가 여러 아이를 관리한다는 IA 요구를 반영해
-- 1:N으로 설계한다(예전에는 app_user.child_name 단일 문자열이 유일한 표현이었다).
create table if not exists public.parent_child (
    id uuid primary key,
    parent_id uuid not null references public.app_user(id) on delete cascade,
    name varchar(40) not null,
    age_band varchar(20) not null,
    avatar_key varchar(32) not null,
    gender varchar(8),
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index if not exists parent_child_parent_id_idx on public.parent_child (parent_id, created_at);
