-- 스토리 제작 규약 §2.3 "캐릭터 페르소나 정의" - cast.yaml(목소리)/캐릭터북(외형)이 여기서
-- 파생된다. 상시 질문(§4)/인물과 대화(§5.2) 기능이 스포일러 없이 답하기 위한 knowledge_* 필드가
-- 핵심(StoryPersona.java 참고). 이 마이그레이션 파일이 처음 도입될 때는 아직 어떤 스토리도
-- personas.yaml을 임포트한 적이 없을 수 있으므로(story_persona.sql이 story.sql보다 먼저 실행될
-- 일은 없지만, story 테이블에 해당 storyId 행이 없는 채로 이 파일만 먼저 배포될 수는 있다) FK는
-- 걸어 두되 seed insert는 하지 않는다 - StoryImportService의 실제 임포트가 유일한 쓰기 경로다.
create table if not exists public.story_persona (
    id uuid not null,
    story_id varchar(255) not null references public.story(id) on delete cascade,
    cast_tag varchar(255) not null,
    speaker_id varchar(255) not null,
    role varchar(255) not null,
    age_band varchar(255) not null,
    personality_traits jsonb not null,
    personality_one_liner varchar(300) not null,
    speech_endings jsonb not null,
    speech_catchphrases jsonb not null,
    sentence_length_bias varchar(255) not null,
    emotion_range_allowed jsonb not null,
    emotion_cap_note varchar(300) not null,
    voice_texture jsonb not null,
    appearance_facts jsonb not null,
    relationships jsonb not null,
    knowledge_knows jsonb not null,
    knowledge_does_not_know jsonb not null,
    knowledge_never_reveals_first jsonb not null,
    same_person_key varchar(255),
    primary key (id),
    unique (story_id, cast_tag)
);
