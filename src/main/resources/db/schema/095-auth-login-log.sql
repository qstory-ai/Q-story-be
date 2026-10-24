-- 로그인 기록(10/24). 지금까지는 가입(app_user.created_at)만 남고 로그인은 어디에도 없어서 "로그인한 사람 수"를 볼 수 없었다.
-- 비밀번호·구글·카카오 로그인은 할 때마다 한 줄, 로그인 유지(토큰 갱신, REFRESH)는 하루에 한 사람 한 줄만 남긴다 -
-- 로그인 유지로 다시 들어온 사람도 "그날 들어온 사람"으로 세기 위해서다. 계정 id·역할·방식·시각만 둔다(주소·기기 없음).
-- 계정 행이 지워지면 같이 지워진다.
create table if not exists public.auth_login_log (
    id bigserial primary key,
    user_id uuid not null references public.app_user (id) on delete cascade,
    role varchar(20) not null,
    method varchar(20) not null,
    login_day date not null,
    created_at timestamptz not null default now()
);

create index if not exists auth_login_log_created_at_idx on public.auth_login_log (created_at);
create unique index if not exists auth_login_log_refresh_once_per_day
    on public.auth_login_log (user_id, login_day) where method = 'REFRESH';

alter table public.auth_login_log enable row level security;

-- 그라파나용 - 계정 id 대신 가린 키(같은 사람인지만 셀 수 있게). OAuth 첫 로그인은 곧 가입이라 signup으로 표시한다.
create or replace view grafana.logins as
select
    l.created_at,
    l.login_day,
    md5(l.user_id::text) as user_key,
    case l.role when 'PARENT' then '보호자' when 'TUTOR' then '선생님' when 'DIRECTOR' then '원장' else l.role end as role,
    case l.method when 'PASSWORD' then '아이디·비밀번호' when 'GOOGLE' then '구글' when 'KAKAO' then '카카오' when 'REFRESH' then '로그인 유지' else l.method end as method,
    l.method <> 'REFRESH' and l.created_at - u.created_at < interval '1 minute' as is_signup,
    l.role not in ('PARENT', 'TUTOR', 'DIRECTOR')
        or grafana.looks_like_test(array[u.login_id, u.display_name, u.child_name], u.email) as is_test
from public.auth_login_log l
join public.app_user u on u.id = l.user_id;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on public.auth_login_log, grafana.logins from anon';
        execute 'revoke all on sequence public.auth_login_log_id_seq from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on public.auth_login_log, grafana.logins from authenticated';
        execute 'revoke all on sequence public.auth_login_log_id_seq from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant select on grafana.logins to grafana_reader';
    end if;
end
$$;
