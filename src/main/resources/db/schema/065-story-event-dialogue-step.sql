-- Q-31 그레텔 대화: 대화 한 단계(아이 말, 도움 단계, 행동 확인, 마무리)를 DIALOGUE_STEP 이벤트로 남긴다.
-- 063·064는 열려 있는 PR(Q-35·Q-37)이 쓰므로 065를 쓴다.
alter table public.story_events drop constraint if exists story_events_event_name_check;
alter table public.story_events add constraint story_events_event_name_check check (event_name in (
    'LANDING_VIEW','LANDING_CTA_CLICK','STORY_STARTED','SCENE_REACHED','QUESTION_INVITE_SHOWN','QUESTION_SKIPPED',
    'QUESTION_STARTED','CHOICE_SELECTED','QUESTION_RESULT','PLAYBACK_ISSUE','EXPLICIT_EXIT','STORY_COMPLETED',
    'PARENT_REPORT_OPENED','SURVEY_OPENED','DIALOGUE_STEP'));
