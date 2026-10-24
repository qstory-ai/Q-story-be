-- 대화 품질 대시보드 고도화(10/24) - 재생 조작(PLAYBACK_CONTROL)의 action(장면 넘기기·일시정지 등)을 장면별로 세려고
-- grafana.story_events 뒤에 action 칸을 붙인다(create or replace view는 칸을 뒤에만 더할 수 있다). 권한은 066 그대로.

create or replace view grafana.story_events as
select
    e.occurred_at,
    e.event_name,
    e.session_id,
    s.story_id,
    s.traffic_type,
    e.metadata ->> 'anchor_id' as anchor_id,
    e.metadata ->> 'scene_id' as scene_id,
    e.metadata ->> 'route' as route,
    e.metadata ->> 'result' as result,
    e.metadata ->> 'fail_reason' as fail_reason,
    e.metadata ->> 'issue_type' as issue_type,
    e.metadata ->> 'reason_code' as reason_code,
    e.metadata ->> 'action' as action
from public.story_events e
join public.story_sessions s on s.id = e.session_id;
