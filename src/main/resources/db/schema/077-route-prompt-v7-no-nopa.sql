-- 라우팅 프롬프트 V7: V6의 예시·선택지 이름에 남아 있던 "노파"를 "할머니"로 바꾼 버전(PM 피드백 - 아이에게
-- "노파"가 들렸다). 예시는 모두 과자집 문 앞(정체가 드러나기 전) 장면이라 "할머니"가 맞다.
-- 적용된 027/007을 고치지 않고, V6 행을 복사해 새 버전으로 만든다. 프론트 콘텐츠도 같은 버전
-- (content/prompts/qstory-route-prompt-v7-coverage.yaml)로 올라가므로 다음 스토리 임포트와 내용이 같다 -
-- 임포트 전에도 바로 쓰이도록 V6를 쓰던 스토리를 V7로 옮긴다. 그레텔 대화의 안전 규칙 조각
-- (companion_safety_fragment)은 임포트가 건드리지 않으므로 여기서 함께 복사한다.
insert into public.route_prompt (version, system_text, instruction_text, companion_safety_fragment)
select 'QSTORY_ROUTE_PROMPT_V7_COVERAGE',
       replace(system_text, '노파', '할머니'),
       replace(instruction_text, '노파', '할머니'),
       companion_safety_fragment
from public.route_prompt
where version = 'QSTORY_ROUTE_PROMPT_V6_COVERAGE'
on conflict (version) do nothing;

insert into public.route_prompt_stage (route_prompt_version, stage, system_text, examples_json)
select 'QSTORY_ROUTE_PROMPT_V7_COVERAGE',
       stage,
       replace(system_text, '노파', '할머니'),
       replace(examples_json::text, '노파', '할머니')::jsonb
from public.route_prompt_stage
where route_prompt_version = 'QSTORY_ROUTE_PROMPT_V6_COVERAGE'
on conflict (route_prompt_version, stage) do nothing;

update public.story
set route_prompt_version = 'QSTORY_ROUTE_PROMPT_V7_COVERAGE'
where route_prompt_version = 'QSTORY_ROUTE_PROMPT_V6_COVERAGE'
  and exists (select 1 from public.route_prompt where version = 'QSTORY_ROUTE_PROMPT_V7_COVERAGE');
