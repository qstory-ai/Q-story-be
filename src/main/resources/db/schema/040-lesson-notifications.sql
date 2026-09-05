-- 수업예정/수업완료 알림 on/off - notification_settings에 계속 컬럼을 늘리는 기존 관례를 따른다
-- (031-notification-settings.sql 헤더 참고). 둘 다 opt-in(true) 기본값 - marketing_enabled와
-- 같은 이유(가입 동의에 이미 포함)이고, 새로 추가하는 알림이 기본으로 꺼진 채 조용히 묻히지
-- 않게 하려는 것.
alter table public.notification_settings
    add column if not exists lesson_reminder_enabled boolean not null default true,
    add column if not exists lesson_report_enabled boolean not null default true;
