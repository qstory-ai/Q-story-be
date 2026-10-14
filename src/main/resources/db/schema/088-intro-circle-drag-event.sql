-- 랜딩 소개 구역 원 끌기(INTRO_CIRCLE_DRAG) 이벤트 이름 허용. 070의 목록에 하나를 더한다(metadata는 원 이름만).
alter table public.story_events drop constraint if exists story_events_event_name_check;
alter table public.story_events add constraint story_events_event_name_check check (event_name in (
    'LANDING_VIEW','LANDING_CTA_CLICK','INTRO_CIRCLE_DRAG','STORY_STARTED','SCENE_REACHED','QUESTION_INVITE_SHOWN','QUESTION_SKIPPED',
    'QUESTION_STARTED','CHOICE_SELECTED','QUESTION_RESULT','PLAYBACK_ISSUE','EXPLICIT_EXIT','STORY_COMPLETED',
    'PARENT_REPORT_OPENED','SURVEY_OPENED','DIALOGUE_STEP',
    'APP_ENTRY','SIGNUP_STARTED','SIGNUP_COMPLETED','CHILD_REGISTERED','CLASS_JOIN','CONSENT_SAVED',
    'PLAYBACK_CONTROL','REPORT_VIEWED','REPORT_ACTION'));
