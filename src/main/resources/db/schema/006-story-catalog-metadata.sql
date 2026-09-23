-- 홈 화면 스토리 라이브러리/상세 페이지가 보여줄 메타데이터. 지금까지 story 테이블은 콘텐츠
-- 버전 관리용 필드뿐이라 "무슨 이야기인지, 어떻게 생겼는지"를 사용자에게 보여줄 데이터가 없었다.
alter table if exists public.story
    add column if not exists cover_image_url varchar(500);
alter table if exists public.story
    add column if not exists description varchar(1000);
alter table if exists public.story
    add column if not exists category varchar(64);

-- cover_image_url은 이 파일이 쓰일 당시 프론트엔드(fe/q-story-web) public/ 폴더 기준 경로였다.
-- 지금은 삽화가 Supabase 공개 버킷에서 서빙되고, StoryAssetUrls.forCover()가 이 "/story/<slug>/..."
-- 접두사를 알아보고 버킷 URL로 바꿔 내려주므로 저장된 값은 그대로 둔다(재마이그레이션 불필요).
update public.story
set cover_image_url = '/story/hansel-gretel/illustrations/hg-art-08-candy-house-reveal.jpg',
    description = '길을 잃은 남매가 서로에게 묻고 답하며 숲을 되돌아 나오는 이야기예요.',
    category = '고전동화'
where id = 'HG';
