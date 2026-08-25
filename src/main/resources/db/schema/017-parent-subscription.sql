-- 학부모(PARENT) 개인 구독 - 지금까지는 기관(organization)의 subscription_status만 있었고,
-- 학부모 본인이 유치원과 무관하게 개인 결제로 전체 서재를 이용하는 경로는 없었다. 같은
-- SubscriptionStatus 상태 모델(NONE/TRIALING/ACTIVE/EXPIRED)을 app_user에도 둔다 - 결제
-- 게이트웨이는 아직 연동되어 있지 않으며, org와 마찬가지로 지금은 상태/게이트일 뿐이다.
alter table public.app_user
    add column if not exists subscription_status varchar(255) not null default 'NONE';

alter table public.app_user
    add column if not exists subscription_updated_at timestamp(6) with time zone;

alter table public.app_user drop constraint if exists app_user_subscription_status_check;

alter table public.app_user
    add constraint app_user_subscription_status_check
    check (subscription_status in ('NONE', 'TRIALING', 'ACTIVE', 'EXPIRED'));
