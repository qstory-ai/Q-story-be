-- 비용 관리 2단계(10/25): 구글 클라우드 실제 청구액. 결제 계정의 BigQuery 내보내기(표준 사용 비용)를 백엔드가 6시간마다 읽어
-- 날짜(미국 태평양 - 구글 청구 기준)·서비스·SKU별로 넣는다. 크레딧(무료 사용량 등)은 뺀 순비용.
create table if not exists public.provider_cost_daily (
    provider varchar(40) not null,
    usage_date date not null,
    service text not null,
    sku text not null,
    currency varchar(8) not null,
    cost numeric(14, 6) not null,
    cost_usd numeric(14, 6),
    updated_at timestamptz not null default now(),
    primary key (provider, usage_date, service, sku)
);
alter table public.provider_cost_daily enable row level security;

create or replace view grafana.gcp_cost_daily as
select usage_date, service, sku,
       coalesce(cost_usd, case when currency = 'USD' then cost end) as cost_usd
from public.provider_cost_daily
where provider = 'gcp';

-- 구글 하루 비용 - 실제 청구가 들어온 날은 실제, 아직 없는 날(내보내기 전·당일)은 음성 길이로 잡은 추정.
create or replace view grafana.google_cost_daily as
with actual as (
    select usage_date, sum(cost_usd) as cost_usd from grafana.gcp_cost_daily group by 1
)
select coalesce(a.usage_date, e.usage_date) as usage_date,
       coalesce(a.cost_usd, e.est_cost_usd) as cost_usd,
       case when a.usage_date is null then '추정' else '실제' end as basis
from actual a
full join grafana.tts_audio_daily e on e.usage_date = a.usage_date;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on public.provider_cost_daily, grafana.gcp_cost_daily, grafana.google_cost_daily from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on public.provider_cost_daily, grafana.gcp_cost_daily, grafana.google_cost_daily from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant select on grafana.gcp_cost_daily, grafana.google_cost_daily to grafana_reader';
    end if;
end
$$;
