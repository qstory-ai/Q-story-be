-- LaunchNotificationRequest - 무료 데모(/demo)를 쓰기 전 보호자가 남기는, 정식 출시 때
-- 연락해 달라는 익명 신청 한 건. 로그인 계정과 무관하다(app_user와 FK 없음).
create table if not exists public.launch_notification_requests (
    id uuid primary key,
    parent_name varchar(60) not null,
    email varchar(254) not null,
    phone varchar(30) not null,
    child_gender varchar(255) not null,
    child_age integer not null,
    discovery_source varchar(200) not null,
    created_at timestamptz not null
);

create index if not exists launch_notification_requests_created_at_idx
    on public.launch_notification_requests (created_at desc);
