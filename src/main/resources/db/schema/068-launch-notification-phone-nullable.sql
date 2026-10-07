-- 연락을 원치 않는("괜찮아요") 신청은 전화번호를 저장하지 않는다.
alter table launch_notification_requests alter column phone drop not null;
