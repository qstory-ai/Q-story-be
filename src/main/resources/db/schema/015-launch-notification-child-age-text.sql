-- 아이 나이를 정수가 아니라 자유 텍스트("5세", "3개월")로 받는다 - 돌 전 아이는 개월 수로
-- 말하는 경우가 많아 숫자로 강제하지 않는다. using child_age::text로 기존 정수 값도 그대로
-- 문자열로 옮겨간다 - 이미 varchar여도 같은 타입으로의 재캐스팅이라 매 부팅 재실행에 안전하다.
alter table public.launch_notification_requests
    alter column child_age type varchar(20) using child_age::text;
