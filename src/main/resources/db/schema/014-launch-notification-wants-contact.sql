-- "연락 받고 싶어요"/"괜찮아요" 두 버튼 모두 정보를 받되, 능동적 연락을 원하는지만 구분한다.
-- default true는 이 컬럼이 생기기 전에 들어온 기존 행(있었다면)을 위한 것으로, 신규 제출은
-- 항상 서비스단에서 명시적으로 값을 채워 보낸다.
alter table public.launch_notification_requests
    add column if not exists wants_contact boolean not null default true;
