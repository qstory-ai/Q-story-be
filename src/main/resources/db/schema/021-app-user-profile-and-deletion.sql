-- 마이페이지 "내 정보 관리"/"회원 탈퇴" 기반 - 학부모(PARENT)가 자녀 이름을 저장할 곳이 지금까지
-- 전혀 없었고(런타임 개인화는 narration 쪽 텍스트 치환일 뿐 프로필에 저장되지 않는다), 회원 탈퇴는
-- 완전 삭제 대신 소프트 삭제로 처리한다 - 연관 테이블(비밀번호 재설정 토큰, 튜터 학생, 스토리 완료
-- 기록 등)과의 FK 충돌을 피하고 법적/분쟁 대비 복구 여지를 남기기 위함이다.
alter table public.app_user
    add column if not exists child_name varchar(255);

alter table public.app_user
    add column if not exists deleted_at timestamptz;
