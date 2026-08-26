-- TUTOR - 가정을 방문해 1:1 수업을 진행하는 방문 선생님. DIRECTOR/PARENT와 같은 셀프서비스
-- 고객 역할이다(Role.java 참고) - STAFF와 달리 관리자 게이트 없이 공개 회원가입 엔드포인트로
-- 발급된다. app_user.role의 체크 제약을 넓혀야 한다(008-staff-role.sql과 동일 패턴).
alter table if exists public.app_user drop constraint if exists app_user_role_check;

alter table if exists public.app_user
    add constraint app_user_role_check check (role in ('DIRECTOR', 'CLASS_ACCOUNT', 'PARENT', 'TUTOR', 'STAFF'));
