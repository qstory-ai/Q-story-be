-- 공공데이터 기관 목록(유치원·어린이집)과 선생님의 기관 소속 신청.
--
-- 1) public_institution - 원장이 기관을 등록할 때 고르고, 선생님이 소속 신청할 기관을 찾는 목록.
--    원천은 교육부 유치원알리미 공시자료(유치원, 공공누리 제1유형)와 어린이집정보공개포털(어린이집).
--    기관 정보만 담는다 - 원천 파일의 대표자명·원장명 같은 사람 이름과 전화·팩스는 적재하지 않는다.
--    적재는 운영 중 앱이 아니라 PublicInstitutionImportMain(gradle importPublicInstitutions)이 한다 - ops/public-institutions/README.md.
--    kind + source_key가 원천의 한 기관이다. 유치원알리미 다운로드 파일에는 유치원 코드가 없어
--    "교육지원청|유치원명|설립일"을 키로 쓰고(2026년 1차 7,152곳에서 겹침 없음), 어린이집은 원천의 어린이집 코드(stcode)를 쓴다.
create table if not exists public.public_institution (
    id uuid primary key default gen_random_uuid(),
    kind varchar(16) not null check (kind in ('KINDERGARTEN', 'DAYCARE')),
    source varchar(32) not null,
    source_key varchar(200) not null,
    name varchar(200) not null,
    address varchar(300),
    sido varchar(40),
    sigungu varchar(40),
    -- 유치원 설립유형(공립(병설)·사립(사인) …) / 어린이집 유형(국공립·민간·가정 …)
    establishment_type varchar(40),
    -- ACTIVE: 원천에 지금 있는 기관. CLOSED: 원천에서 폐지됐거나 최근 적재에서 빠진 기관(검색에 안 나온다).
    status varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE', 'CLOSED')),
    -- 원천 기준(예: "유치원알리미 2026년 1차 공시", "어린이집정보공개포털 2026-09-30")과 적재 회차 표시.
    source_reference varchar(80),
    import_batch varchar(40),
    -- 검색용 - 이름/주소의 공백을 빼고 소문자로(PublicInstitutionText.searchKey). 이름 먼저, 주소를 뒤에 붙인다.
    search_name varchar(200) not null,
    search_text varchar(520) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (kind, source_key)
);

create index if not exists public_institution_region_idx
    on public.public_institution (sido, sigungu) where status = 'ACTIVE';

-- 이름·주소 일부로 찾는다(LIKE '%…%'). pg_trgm GIN 색인이 있으면 쓰고, 확장을 만들 수 없는 환경이면 색인 없이 둔다 -
-- 전국 유치원·어린이집을 합쳐도 수만 건이라 지역 조건과 함께면 순차 탐색도 견딜 만하다.
-- Supabase는 확장을 extensions 스키마에 두라고 권하므로 그 스키마가 있으면 그쪽에 만든다.
do $$
declare
    trgm_schema text;
begin
    begin
        if exists (select 1 from pg_namespace where nspname = 'extensions') then
            create extension if not exists pg_trgm with schema extensions;
        else
            create extension if not exists pg_trgm;
        end if;
    exception when others then
        raise notice 'pg_trgm unavailable (%), skipping trigram index', sqlerrm;
    end;
    select n.nspname into trgm_schema
    from pg_opclass c join pg_namespace n on n.oid = c.opcnamespace
    where c.opcname = 'gin_trgm_ops'
    limit 1;
    if trgm_schema is not null then
        execute format(
            'create index if not exists public_institution_search_trgm_idx on public.public_institution '
            'using gin (search_text %I.gin_trgm_ops) where status = ''ACTIVE''', trgm_schema);
    end if;
end
$$;

-- 2) 기관이 어느 공공 기관인지. 한 공공 기관은 한 기관(원장)만 등록할 수 있다(unique) - 중복 등록을 DB가 막는다.
--    공공 목록에서 지워져도 기관은 남는다(set null).
alter table public.organization
    add column if not exists public_institution_id uuid
        references public.public_institution(id) on delete set null;

create unique index if not exists organization_public_institution_uidx
    on public.organization (public_institution_id) where public_institution_id is not null;

-- 3) 선생님의 기관 소속 신청. 원장이 수락하면 초대 코드 수락과 같은 organization_tutor 행이 생긴다.
--    한 선생님이 한 기관에 걸어 둔 대기 신청은 하나뿐이다(부분 유니크). 거절·취소 뒤에는 다시 신청할 수 있다.
--    처리한 원장은 따로 적지 않는다 - 기관의 원장은 한 명이고, 탈퇴 정리(AccountErasureService)를 늘리지 않으려고.
create table if not exists public.organization_join_request (
    id uuid primary key,
    organization_id uuid not null references public.organization(id) on delete cascade,
    tutor_id uuid not null references public.app_user(id) on delete cascade,
    status varchar(16) not null check (status in ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED')),
    created_at timestamptz not null,
    decided_at timestamptz
);

create unique index if not exists organization_join_request_pending_uidx
    on public.organization_join_request (organization_id, tutor_id) where status = 'PENDING';
create index if not exists organization_join_request_org_idx
    on public.organization_join_request (organization_id, status, created_at desc);
create index if not exists organization_join_request_tutor_idx
    on public.organization_join_request (tutor_id, created_at desc);

-- 081과 같이 새 테이블도 Supabase REST로 열리지 않게 잠근다(정책 없이 RLS만 - 백엔드는 소유자라 우회한다).
alter table public.public_institution enable row level security;
alter table public.organization_join_request enable row level security;
do $$
declare
    r text;
begin
    foreach r in array array['anon', 'authenticated'] loop
        if exists (select 1 from pg_roles where rolname = r) then
            execute format('revoke all on public.public_institution from %I', r);
            execute format('revoke all on public.organization_join_request from %I', r);
        end if;
    end loop;
end
$$;
