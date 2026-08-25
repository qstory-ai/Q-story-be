-- "괜찮아요"(연락받지 않기)를 고른 보호자는 이메일 없이 신청할 수 있어야 한다.
-- alter column ... drop not null은 이미 nullable이어도 에러 없이 통과하므로 매 부팅 재실행에 안전하다.
alter table public.launch_notification_requests
    alter column email drop not null;
