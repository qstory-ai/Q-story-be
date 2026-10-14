-- PartnershipInquiry - 소개 페이지(qstory.ai.kr) "협업하기"로 기관·출판사가 남기는 협업 문의 한 건.
-- 로그인 계정과 무관한 익명 제출이다(app_user와 FK 없음).
-- 보관 목적: 협업 문의에 답하고 협업을 논의하기 위한 연락. 보관 기간: 수집일로부터 1년(다른 선택 참여 양식과 같게) -
-- PartnershipInquiryRetentionScheduler가 매일 지운다. 개인정보 처리방침 제1조 5항·제3조 1항 표에 "협업 문의" 행을 함께 둔다.
create table if not exists public.partnership_inquiries (
    id uuid primary key,
    name varchar(60) not null,
    affiliation varchar(100) not null,
    role varchar(20) not null,
    job_title varchar(60),
    email varchar(254) not null,
    phone varchar(30),
    message varchar(2000) not null,
    created_at timestamptz not null
);

create index if not exists partnership_inquiries_created_at_idx
    on public.partnership_inquiries (created_at desc);

-- 081·086과 같이 정책 없이 RLS만 켠다(백엔드는 소유자로 연결해 우회한다).
alter table public.partnership_inquiries enable row level security;
do $$
declare
    r text;
begin
    foreach r in array array['anon', 'authenticated'] loop
        if exists (select 1 from pg_roles where rolname = r) then
            execute format('revoke all on public.partnership_inquiries from %I', r);
        end if;
    end loop;
end
$$;
