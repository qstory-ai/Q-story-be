-- 스토리 제작 규약 §5.1 "독서 토론 - 책의 주제로 AI와 대화하기". 완주 후 대화 기능이 읽어갈
-- 주제 뱅크. StoryImportService의 실제 임포트가 유일한 쓰기 경로라 seed insert는 없다.
create table if not exists public.story_discussion_topic (
    id varchar(255) not null,
    story_id varchar(255) not null references public.story(id) on delete cascade,
    statement varchar(500) not null,
    related_scene_ids jsonb not null,
    related_asset_ids jsonb not null,
    age_hint varchar(300) not null,
    safety_no_forced_answer boolean not null,
    safety_respect_child_opinion boolean not null,
    primary key (id)
);
