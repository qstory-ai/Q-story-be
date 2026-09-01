-- 사용자별 알림 preference. app_user에 컬럼을 늘리지 않고 별도 테이블에 두는 이유:
-- 앞으로 (주간 리포트 알림, 아이 학습 알림 등) 항목이 계속 늘 텐데 그때마다 스키마 마이그
-- 레이션이 필요해지지 않게 하려는 것. 지금은 marketing 하나뿐이지만 여기에 새 컬럼을 추가
-- 하는 방식으로 계속 확장한다(더 늘어나면 JSON preferences 컬럼으로 옮기는 것을 검토).
create table if not exists public.notification_settings (
    user_id uuid primary key references public.app_user(id) on delete cascade,
    -- IA "마이페이지 > 알림 설정 > 마케팅 알림 (새 작품 출시)". 기본값은 opt-in(true) - 처음
    -- 가입 시 함께 받은 약관 동의에 마케팅 항목이 있어서.
    marketing_enabled boolean not null default true,
    updated_at timestamptz not null
);
