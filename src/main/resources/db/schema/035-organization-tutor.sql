-- 기관-선생님 소속 관계. IA "기관 관리자 > 선생님 관리 > 선생님 초대"의 결과. 기존 AppUser
-- .organizationId(DIRECTOR/CLASS_ACCOUNT용 필드)와 별개로, TUTOR가 여러 기관에 소속될 수 있는
-- 가능성을 열어 두기 위해 별도 조인 테이블로 만든다(현재 UI는 한 기관만 노출하지만 스키마는
-- 확장 가능). 삭제는 두 쪽 어느 쪽이든 지워지면 자동으로 정리된다.
create table if not exists public.organization_tutor (
    id uuid primary key,
    organization_id uuid not null references public.organization(id) on delete cascade,
    tutor_id uuid not null references public.app_user(id) on delete cascade,
    joined_at timestamptz not null,
    unique (organization_id, tutor_id)
);

create index if not exists organization_tutor_org_idx on public.organization_tutor (organization_id);
create index if not exists organization_tutor_tutor_idx on public.organization_tutor (tutor_id);

-- 기관 관리자가 선생님을 초대할 때 발급하는 1회용 링크/코드. tutor_invite/class_invite와 같은
-- 방식이다: 원본 token은 즉시 반환하고 sha-256 해시만 저장하며, 사람이 손으로 옮길 수 있는
-- short_code도 함께 발급한다(JoinCodeGenerator 재사용).
create table if not exists public.organization_tutor_invite (
    id uuid primary key,
    organization_id uuid not null references public.organization(id) on delete cascade,
    token_hash varchar(255) not null unique,
    short_code varchar(16) not null unique,
    expires_at timestamptz not null,
    used_at timestamptz,
    used_by_tutor_id uuid references public.app_user(id) on delete set null,
    created_at timestamptz not null
);

create index if not exists organization_tutor_invite_org_idx
    on public.organization_tutor_invite (organization_id, created_at desc);
