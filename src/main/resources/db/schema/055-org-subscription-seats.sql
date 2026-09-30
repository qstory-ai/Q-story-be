-- 기관 이용권을 학생 수 기준으로 과금한다. 결제한 인원(seat)은 organization.subscription_seats에 남기고,
-- 주문에는 그 시점의 학생 수를 남겨 금액을 검증·추적한다. subscription_seats가 null이면 인원 제한 없이
-- 결제된 예전 구독이다(정액 결제 시절) - 다음 결제 때부터 인원이 기록된다.
alter table public.organization add column if not exists subscription_seats integer;
alter table public.payment_order add column if not exists student_count integer;
