-- 스토리 제작 규약 대원칙 "재현 가능성" - 삽화 한 장의 생성 근거(프롬프트/모델/입력 해시)를
-- 기록한다. status가 RECOVERED가 아니면 그 필드들은 비워두고 note에 이유를 남긴다
-- (StoryVisualProvenance.java 참고) - 없는 값을 지어내지 않는다.
create table if not exists public.story_visual_provenance (
    id uuid not null,
    story_id varchar(255) not null references public.story(id) on delete cascade,
    asset_slug varchar(255) not null,
    status varchar(32) not null check (status in ('RECOVERED', 'LOST', 'UNKNOWN')),
    prompt text,
    model varchar(255),
    input_hash varchar(255),
    generated_at timestamp(6) with time zone,
    approved_by varchar(255),
    note varchar(500),
    primary key (id),
    unique (story_id, asset_slug)
);
