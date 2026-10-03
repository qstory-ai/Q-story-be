-- Q-37 결제 내역: Toss 승인 응답의 영수증 URL(receipt.url)을 저장해 결제 내역에서 영수증 링크로 보여 준다.
-- 이 파일 이전에 승인된 결제는 값이 없어 링크 없이 보인다.
alter table public.payment_order add column if not exists receipt_url text;
-- 기관 결제 내역 조회(organization_id + 최근 결제순)용. 사용자 조회는 payment_order_user_created_idx로 충분하다.
create index if not exists payment_order_organization_paid_idx on public.payment_order (organization_id, paid_at desc);
