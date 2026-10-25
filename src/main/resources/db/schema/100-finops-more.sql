-- 비용 관리 3단계(10/25): Higgsfield 실시간 삽화, Railway 서버, 고정 월 요금(Supabase·Vercel 등).
-- 1) Higgsfield는 잔액·사용 내역 API가 없어 만든 그림 수(realtime_image READY) × 장당 단가(추정)로 본다.
-- 2) Railway는 백엔드가 1시간마다 이번 결제 기간 사용액을 provider_cost_snapshot에 찍는다(provider='railway').
-- 3) 플랜 요금처럼 매달 같은 돈은 finops_fixed_monthly에 직접 적는다 - 비어 있으면(null) 대시보드에 '입력 필요'로 보인다.
alter table public.provider_cost_snapshot add column if not exists period_start timestamptz;
alter table public.provider_cost_snapshot add column if not exists period_end timestamptz;

-- 출력 그림 토큰 100만 개당 $30(Higgsfield 견적 API 설명), 2k·high 한 장을 약 6천 토큰 + 인물 시트 입력으로 잡은 추정.
insert into public.finops_unit_price (provider, unit, usd_per_unit, source)
values ('higgsfield', 'image', 0.2, '추정 - 출력 그림 100만 토큰 $30(Higgsfield 견적 API), 2k·high 한 장 약 $0.2. 콘솔 사용 내역으로 확인 필요')
on conflict (provider, unit) do nothing;

create table if not exists public.finops_fixed_monthly (
    provider varchar(40) primary key,
    usd_per_month numeric(10, 2),
    note text not null,
    updated_at timestamptz not null default now()
);
alter table public.finops_fixed_monthly enable row level security;
insert into public.finops_fixed_monthly (provider, usd_per_month, note) values
    ('supabase', null, 'DB·저장소 플랜 요금 - 입력 필요'),
    ('vercel', null, '웹 배포 플랜 요금 - 입력 필요')
on conflict (provider) do nothing;

-- Higgsfield 하루 비용(한국 날짜) - 실제로 만들어진 그림만(실패·대기는 과금되지 않는다).
create or replace view grafana.higgsfield_cost_daily as
select (created_at at time zone 'Asia/Seoul')::date as usage_date,
       count(*) as images,
       count(*) * (select usd_per_unit from public.finops_unit_price where provider = 'higgsfield' and unit = 'image')
           as est_cost_usd
from public.realtime_image
where status = 'READY'
group by 1;

create or replace view grafana.railway_latest as
select taken_at, usage_monthly_usd as period_usd, period_start, period_end,
       round(usage_monthly_usd / greatest(extract(epoch from (taken_at - period_start)), 3600)
             * extract(epoch from (period_end - period_start)), 2) as projected_usd
from public.provider_cost_snapshot
where provider = 'railway'
order by taken_at desc
limit 1;

create or replace view grafana.finops_fixed_monthly as
select provider, usd_per_month, note, updated_at from public.finops_fixed_monthly;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on public.finops_fixed_monthly, grafana.higgsfield_cost_daily, grafana.railway_latest, grafana.finops_fixed_monthly from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on public.finops_fixed_monthly, grafana.higgsfield_cost_daily, grafana.railway_latest, grafana.finops_fixed_monthly from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant select on grafana.higgsfield_cost_daily, grafana.railway_latest, grafana.finops_fixed_monthly to grafana_reader';
    end if;
end
$$;
