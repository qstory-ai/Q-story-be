-- 보안 점검 M7: Supabase REST(PostgREST)로 public 테이블이 열리지 않게 잠근다.
-- Supabase는 public 스키마를 REST API로 내보내고, 기본 권한으로 anon/authenticated 역할에 모든 테이블의 읽기·쓰기를
-- 준다. 그래서 anon 키(또는 authenticated JWT)가 새면 RLS가 꺼진 테이블은 REST로 그대로 읽고 고칠 수 있었다.
-- 백엔드는 테이블 소유자로 연결해 RLS를 우회하고, Grafana(grafana_reader)는 grafana 스키마의 뷰만 읽으므로
-- 두 경로 모두 이 변경과 상관없다(뷰는 소유자 권한으로 원본 테이블을 읽는다).
-- 072~076에서 새 테이블 몇 개에만 켜던 것을 public 전체로 넓힌다. 다시 실행해도 결과가 같다.

-- 1) public의 모든 테이블(일반·파티션)에 정책 없이 RLS를 켠다 - 정책이 없으면 소유자 외에는 행이 하나도 보이지 않는다.
--    flyway_schema_history는 뺀다 - 마이그레이션 중 Flyway가 다른 연결로 이 표를 잡고 있어서, 여기서 alter table
--    (AccessExclusiveLock)을 걸면 서로 기다리다 부팅이 멈춘다(로컬 검증에서 확인). 이 표도 2)에서 anon/authenticated
--    권한을 거두므로 REST로는 읽히지 않는다.
do $$
declare
    t record;
begin
    for t in
        select c.relname
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public'
          and c.relkind in ('r', 'p')
          and not c.relrowsecurity
          and c.relname <> 'flyway_schema_history'
    loop
        execute format('alter table public.%I enable row level security', t.relname);
    end loop;
end
$$;

-- 2) anon/authenticated의 public 테이블·시퀀스·함수 권한을 거둔다. RLS와 겹쳐 두 겹으로 막는다.
--    (로컬·테스트 Postgres에는 이 역할들이 없으니 있을 때만.)
do $$
declare
    r text;
begin
    foreach r in array array['anon', 'authenticated'] loop
        if exists (select 1 from pg_roles where rolname = r) then
            execute format('revoke all on all tables in schema public from %I', r);
            execute format('revoke all on all sequences in schema public from %I', r);
            execute format('revoke all on all functions in schema public from %I', r);
        end if;
    end loop;
end
$$;

-- 3) 앞으로 만들 객체도 막는다. Supabase는 postgres·supabase_admin이 public에 만드는 객체에 anon/authenticated 권한을
--    기본으로 붙인다. 마이그레이션 계정(현재 역할)이 만드는 객체의 기본 권한은 직접 바꿀 수 있고, 다른 역할
--    (supabase_admin 등)의 기본 권한은 그 역할의 구성원일 때만 바꿀 수 있어서 안 되면 건너뛰고 알림만 남긴다.
do $$
declare
    r text;
    owner_role text;
begin
    foreach r in array array['anon', 'authenticated'] loop
        if not exists (select 1 from pg_roles where rolname = r) then
            continue;
        end if;
        for owner_role in
            select distinct pg_get_userbyid(d.defaclrole)
            from pg_default_acl d
            where d.defaclnamespace = 'public'::regnamespace
            union
            select current_user::text
        loop
            if not pg_has_role(current_user, owner_role, 'MEMBER') then
                raise notice 'skip default privileges for role % (not a member)', owner_role;
                continue;
            end if;
            begin
                execute format('alter default privileges for role %I in schema public revoke all on tables from %I', owner_role, r);
                execute format('alter default privileges for role %I in schema public revoke all on sequences from %I', owner_role, r);
                execute format('alter default privileges for role %I in schema public revoke all on functions from %I', owner_role, r);
            exception when insufficient_privilege then
                raise notice 'skip default privileges for role % (insufficient privilege)', owner_role;
            end;
        end loop;
    end loop;
end
$$;

-- 4) Grafana 읽기 계정은 지금처럼 grafana 뷰만 읽는다(066/070/072/080과 같은 권한을 다시 확인).
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'grafana_reader')
       and exists (select 1 from pg_namespace where nspname = 'grafana') then
        execute 'grant usage on schema grafana to grafana_reader';
        execute 'grant select on all tables in schema grafana to grafana_reader';
    end if;
end
$$;
