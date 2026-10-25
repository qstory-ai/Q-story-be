-- Railway는 프로젝트 토큰으로 지금까지 사용액과 기간 끝 예상(estimatedUsage)을 함께 받는다 - 결제 기간 날짜는 계정 토큰이
-- 있어야 보여서 비워 두고, 예상은 Railway가 낸 값을 그대로 쓴다(없던 기록은 예전처럼 기간 진행률로 계산).
alter table public.provider_cost_snapshot add column if not exists projected_usd numeric(12, 6);

create or replace view grafana.railway_latest as
select taken_at, usage_monthly_usd as period_usd, period_start, period_end,
       round(coalesce(projected_usd,
                      usage_monthly_usd / greatest(extract(epoch from (taken_at - period_start)), 3600)
                          * extract(epoch from (period_end - period_start))), 2) as projected_usd
from public.provider_cost_snapshot
where provider = 'railway'
order by taken_at desc
limit 1;
