-- 토큰 무효화(보안 점검 M4). 지금까지 발급한 토큰은 만료(최대 90일) 전까지 비밀번호를 바꾸거나 탈퇴해도 계속 통했다.
-- 계정마다 토큰 버전을 두고 토큰(tv claim)에 함께 싣는다 - 비밀번호 변경·재설정·탈퇴 때 올리면 그 전에 발급한 토큰은
-- JwtAuthFilter에서 인증되지 않는다. tv가 없는 예전 토큰은 0으로 보므로, 이 컬럼의 기본값 0과 맞아 배포 직후에도 그대로 통한다.
alter table public.app_user add column if not exists token_version integer not null default 0;
