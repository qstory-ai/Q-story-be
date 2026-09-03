-- 앱 내 알림. IA에서 홈 상단의 벨 버튼과 알림 센터를 요구했다. push 알림·이메일은 아직
-- 범위가 아니고, 지금은 로그인한 사용자가 홈에 왔을 때 벨을 눌러 확인하는 "in-app" 알림
-- 만 다룬다. 각 도메인 서비스(TutorReportService/TutorService/OrganizationTutorService 등)가
-- NotificationPublisher를 통해 이 테이블에 이벤트를 적재하고, FE는 GET /v1/notifications
-- 로 조회한다.
--
-- dedup_key: 같은 이벤트가 여러 번 발행되는 것을 방지하기 위한 unique 필드
-- (예: "tutor-report:{completionId}"). 프로듀서가 idempotent하도록 값을 정한다.
-- href: FE에서 알림 클릭 시 이동할 앱 내부 경로. NULL이면 알림만 보고 마감.
-- read_at: NULL이면 unread. 읽음 표시는 timestamp를 기록해 UX와 감사 용도로 남긴다.
create table if not exists public.notifications (
    id uuid primary key,
    user_id uuid not null references public.app_user(id) on delete cascade,
    kind varchar(48) not null,
    title varchar(160) not null,
    body varchar(400),
    href varchar(255),
    read_at timestamptz,
    created_at timestamptz not null,
    dedup_key varchar(160)
);

-- 목록 조회는 항상 (user_id, created_at desc) 순서라 이 순서로 인덱스.
create index if not exists idx_notifications_user_created
    on public.notifications (user_id, created_at desc);

-- dedup_key는 있을 때만 유일해야 한다 - 없는 알림은 매번 새로 발행되도 문제없다.
create unique index if not exists idx_notifications_dedup
    on public.notifications (user_id, dedup_key)
    where dedup_key is not null;
