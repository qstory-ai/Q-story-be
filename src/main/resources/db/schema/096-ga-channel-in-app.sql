-- 유입 경로(channel)에 '앱 안 이동'을 더한다(10/24). 리포트를 열 때 GA로 보낸 이벤트 값 source(history·live·story_end·
-- home_card 등)를 GA가 세션 소스로 읽어, 그 세션이 '기타' 유입으로 잡혔다. 프론트는 view_source로 고쳤고(fe #136),
-- 이미 쌓인 GA 기록은 여기서 '앱 안 이동'으로 따로 묶는다. 나머지는 085와 같다(권한도 그대로).

create or replace view grafana.ga_daily_events as
select
    m.property_id,
    m.metric_date,
    case
        when m.host_name in ('localhost', '127.0.0.1') or m.host_name like '192.168.%' or m.host_name like '10.%' then 'DEV'
        when m.host_name like '%.vercel.app' then 'QA'
        else 'BETA'
    end as traffic_type,
    case
        when m.host_name in ('qstory.ai.kr', 'www.qstory.ai.kr', 'start.qstory.ai.kr') then '랜딩'
        when m.host_name in ('demo.qstory.ai.kr', 'app.qstory.ai.kr', 'play.qstory.ai.kr') or m.qs_surface = 'app' then '앱'
        else '기타'
    end as surface,
    case
        when m.session_source = 'qr' or m.session_medium = 'offline' then 'QR'
        when m.session_source = '(direct)' then '직접'
        when m.session_medium = 'organic' then '검색'
        when m.session_medium in ('cpc', 'ppc', 'paid', 'paidsearch', 'display') then '광고'
        when m.session_medium = 'social' or m.session_source ~* '(instagram|facebook|kakao|band|threads|twitter|youtube|blog\.naver)' then 'SNS'
        when m.session_medium = 'email' then '이메일'
        when m.session_medium = 'referral' then '다른 사이트'
        when m.session_source in ('history', 'live', 'home_card', 'story_end', 'notification', 'list') and m.session_medium = '(not set)' then '앱 안 이동'
        when m.session_source = '(not set)' then '알 수 없음'
        else '기타'
    end as channel,
    m.host_name,
    m.session_source,
    m.session_medium,
    m.session_campaign,
    m.qs_platform,
    m.qs_surface,
    m.event_name,
    m.event_count,
    m.total_users,
    m.sessions
from public.ga_daily_metrics m;

create or replace view grafana.ga_daily_traffic as
select
    t.property_id,
    t.metric_date,
    case
        when t.host_name in ('localhost', '127.0.0.1') or t.host_name like '192.168.%' or t.host_name like '10.%' then 'DEV'
        when t.host_name like '%.vercel.app' then 'QA'
        else 'BETA'
    end as traffic_type,
    case
        when t.host_name in ('qstory.ai.kr', 'www.qstory.ai.kr', 'start.qstory.ai.kr') then '랜딩'
        when t.host_name in ('demo.qstory.ai.kr', 'app.qstory.ai.kr', 'play.qstory.ai.kr') or t.qs_surface = 'app' then '앱'
        else '기타'
    end as surface,
    case
        when t.session_source = 'qr' or t.session_medium = 'offline' then 'QR'
        when t.session_source = '(direct)' then '직접'
        when t.session_medium = 'organic' then '검색'
        when t.session_medium in ('cpc', 'ppc', 'paid', 'paidsearch', 'display') then '광고'
        when t.session_medium = 'social' or t.session_source ~* '(instagram|facebook|kakao|band|threads|twitter|youtube|blog\.naver)' then 'SNS'
        when t.session_medium = 'email' then '이메일'
        when t.session_medium = 'referral' then '다른 사이트'
        when t.session_source in ('history', 'live', 'home_card', 'story_end', 'notification', 'list') and t.session_medium = '(not set)' then '앱 안 이동'
        when t.session_source = '(not set)' then '알 수 없음'
        else '기타'
    end as channel,
    t.host_name,
    t.session_source,
    t.session_medium,
    t.session_campaign,
    t.qs_platform,
    t.qs_surface,
    t.total_users,
    t.new_users,
    t.sessions,
    t.engaged_sessions,
    t.page_views
from public.ga_daily_traffic t;
