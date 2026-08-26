-- 소셜 로그인(구글/카카오) 계정 연결. app_user.password_hash는 이런 계정을 위해 이미
-- nullable로 준비되어 있었다(AppUser.java 주석 참고). oauth_provider/oauth_subject 쌍으로
-- 기존 이메일/비밀번호 계정과 소셜 계정을 구분한다 - 이메일이 같다고 자동으로 연결하지는
-- 않는다(검증되지 않은 provider의 이메일 claim만으로 계정을 합치면 탈취 위험이 있다 -
-- AuthService.loginOrSignupWithOAuth 참고).
alter table public.app_user
    add column if not exists oauth_provider varchar(20);

alter table public.app_user
    add column if not exists oauth_subject varchar(255);

-- do $$ 블록은 쓰지 않는다: Spring Boot의 스크립트 초기화는 ";" 기준으로 파일을 나누고
-- dollar-quoting을 이해하지 못해서, 블록 중간에서 잘려버린다(008-staff-role.sql 참고).
alter table if exists public.app_user drop constraint if exists app_user_oauth_provider_check;

alter table if exists public.app_user
    add constraint app_user_oauth_provider_check
    check (oauth_provider is null or oauth_provider in ('GOOGLE', 'KAKAO'));

-- provider+subject 쌍은 유일해야 한다(같은 구글/카카오 계정으로 두 번 가입할 수 없다). 부분
-- unique index라 oauth_provider/oauth_subject가 둘 다 null인 비밀번호 계정끼리는 서로
-- 제약에 걸리지 않는다.
create unique index if not exists app_user_oauth_identity_key
    on public.app_user (oauth_provider, oauth_subject)
    where oauth_provider is not null and oauth_subject is not null;
