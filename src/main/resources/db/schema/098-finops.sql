-- 비용 관리(FinOps, 10/25). 그라파나 "Q-Story 비용" 대시보드용.
-- 1) OpenRouter(대화·리포트 분석 LLM) 사용액을 1시간마다 찍어 둔다 - 키 API(/api/v1/key·/credits)가 주는 누적·오늘(UTC)·이번 달
--    사용액과 충전액. 모델별 일별 내역(activity)은 관리 키가 있어야 해서 아직 없다.
-- 2) Gemini TTS(그레텔 음성)는 이미 세는 하루 호출 수(provider_daily_usage, 086)와 음성 길이(베타 이벤트 VOICE_DONE의 audio_ms)로 본다.
-- 3) 단가표(finops_unit_price) - 추정 단가는 source에 '추정'으로 남기고, 가격표를 확인하면 이 표만 고친다.
create table if not exists public.provider_cost_snapshot (
    id bigserial primary key,
    provider varchar(40) not null,
    taken_at timestamptz not null default now(),
    usage_total_usd numeric(12, 6),
    usage_daily_usd numeric(12, 6),
    usage_monthly_usd numeric(12, 6),
    credits_total_usd numeric(12, 6)
);
create index if not exists provider_cost_snapshot_taken_idx on public.provider_cost_snapshot (provider, taken_at);

create table if not exists public.finops_unit_price (
    provider varchar(40) not null,
    unit varchar(40) not null,
    usd_per_unit numeric(14, 8) not null,
    source text not null,
    updated_at timestamptz not null default now(),
    primary key (provider, unit)
);

-- Gemini TTS 출력 음성은 초당 25토큰, 출력 음성 토큰 100만 개당 $10로 잡은 추정(gemini-2.5-flash TTS 가격 기준).
insert into public.finops_unit_price (provider, unit, usd_per_unit, source)
values ('gemini-tts', 'audio_second', 0.00025, '추정 - gemini-2.5-flash-preview-tts 가격(출력 음성 100만 토큰 $10, 초당 25토큰). 3.1 TTS 가격표 확인 필요')
on conflict (provider, unit) do nothing;

alter table public.provider_cost_snapshot enable row level security;
alter table public.finops_unit_price enable row level security;

-- OpenRouter 하루 사용액 - usage_daily는 UTC 자정에 0이 되므로 UTC 날짜마다 가장 큰 값이 그날 사용액이다.
create or replace view grafana.openrouter_cost_daily as
select (taken_at at time zone 'UTC')::date as usage_date,
       max(usage_daily_usd) as spend_usd,
       max(usage_monthly_usd) as month_to_date_usd
from public.provider_cost_snapshot
where provider = 'openrouter'
group by 1;

create or replace view grafana.openrouter_latest as
select taken_at, usage_total_usd, usage_daily_usd, usage_monthly_usd, credits_total_usd,
       credits_total_usd - usage_total_usd as balance_usd
from public.provider_cost_snapshot
where provider = 'openrouter'
order by taken_at desc
limit 1;

-- Gemini TTS 하루 호출 수(미국 태평양 날짜 - 한도가 그 기준으로 초기화된다)와 음성 길이로 잡은 추정 비용(한국 날짜).
create or replace view grafana.tts_calls_daily as
select usage_day, calls from public.provider_daily_usage where provider = 'gemini-tts';

create or replace view grafana.tts_audio_daily as
select (e.occurred_at at time zone 'Asia/Seoul')::date as usage_date,
       count(*) as replies,
       sum(case when e.metadata ->> 'audio_ms' ~ '^[0-9]+$' then (e.metadata ->> 'audio_ms')::bigint else 0 end) / 1000.0 as audio_seconds,
       sum(case when e.metadata ->> 'audio_ms' ~ '^[0-9]+$' then (e.metadata ->> 'audio_ms')::bigint else 0 end) / 1000.0
           * (select usd_per_unit from public.finops_unit_price where provider = 'gemini-tts' and unit = 'audio_second')
           as est_cost_usd
from public.story_events e
where e.event_name = 'DIALOGUE_STEP' and e.metadata ->> 'turn_kind' = 'VOICE_DONE'
group by 1;

create or replace view grafana.finops_unit_price as
select provider, unit, usd_per_unit, source, updated_at from public.finops_unit_price;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on public.provider_cost_snapshot, public.finops_unit_price, grafana.openrouter_cost_daily, grafana.openrouter_latest, grafana.tts_calls_daily, grafana.tts_audio_daily, grafana.finops_unit_price from anon';
        execute 'revoke all on sequence public.provider_cost_snapshot_id_seq from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on public.provider_cost_snapshot, public.finops_unit_price, grafana.openrouter_cost_daily, grafana.openrouter_latest, grafana.tts_calls_daily, grafana.tts_audio_daily, grafana.finops_unit_price from authenticated';
        execute 'revoke all on sequence public.provider_cost_snapshot_id_seq from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant select on grafana.openrouter_cost_daily, grafana.openrouter_latest, grafana.tts_calls_daily, grafana.tts_audio_daily, grafana.finops_unit_price to grafana_reader';
    end if;
end
$$;
