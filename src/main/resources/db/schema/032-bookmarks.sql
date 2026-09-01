-- 사용자별 "저장한 작품" (북마크). IA "[2] 서재 > 저장한 작품"과 선생님용 "내 서재 > 저장한
-- 작품"이 같은 저장소를 공유한다 - 부모/선생님 무엇이든 로그인한 계정 자체가 소유자다.
-- story_id는 FK가 아니라 varchar - story_completion과 같은 규약(콘텐츠는 파일로 시드되므로
-- 항상 story 테이블에 존재한다고 보장되지 않는다).
create table if not exists public.bookmark (
    id uuid primary key,
    user_id uuid not null references public.app_user(id) on delete cascade,
    story_id varchar(64) not null,
    created_at timestamptz not null,
    unique (user_id, story_id)
);

create index if not exists bookmark_user_created_idx
    on public.bookmark (user_id, created_at desc);
