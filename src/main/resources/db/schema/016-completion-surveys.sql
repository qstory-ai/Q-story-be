-- CompletionSurvey - 완주 후 부모 리포트 화면에서 남기는 "1분 체험 후기" 한 건. 기존에는 이
-- 문항들을 외부 Google Form으로 리다이렉트해서 받았고(CompletionSurveyController 참고),
-- launch_notification_requests와 마찬가지로 로그인 계정과 무관한 익명 제출이다.
create table if not exists public.completion_surveys (
    id uuid primary key,
    story_id varchar(64) not null,
    child_age_band varchar(20) not null,
    child_engagement integer not null,
    input_understanding varchar(100) not null,
    help_needed varchar(100) not null,
    child_reactions jsonb not null,
    disruptions jsonb not null,
    report_helpfulness integer not null,
    best_aspect varchar(200) not null,
    top_priority varchar(500),
    retry_interest varchar(100) not null,
    one_line_review varchar(500),
    review_usage_consent varchar(100) not null,
    wants_next_stories varchar(100) not null,
    contact varchar(254),
    contact_consent varchar(100) not null,
    created_at timestamptz not null
);

create index if not exists completion_surveys_created_at_idx
    on public.completion_surveys (created_at desc);
