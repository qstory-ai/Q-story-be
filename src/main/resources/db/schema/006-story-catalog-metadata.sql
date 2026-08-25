-- 홈 화면 스토리 라이브러리/상세 페이지가 보여줄 메타데이터. 지금까지 story 테이블은 콘텐츠
-- 버전 관리용 필드뿐이라 "무슨 이야기인지, 어떻게 생겼는지"를 사용자에게 보여줄 데이터가 없었다.
alter table if exists public.story
    add column if not exists cover_image_url varchar(500);
alter table if exists public.story
    add column if not exists description varchar(1000);
alter table if exists public.story
    add column if not exists category varchar(64);

-- cover_image_url은 프론트엔드(fe/q-story-web)의 public/ 폴더에 상대적인 경로다. 백엔드나 CDN이
-- 호스팅하는 자산이 아니다 - story-assets.generated.ts의 기존 삽화 참조 방식과 동일한 규칙을
-- 그대로 재사용한 것으로, 아직 이 제품엔 이미지 업로드/CDN 파이프라인이 없다.
update public.story
set cover_image_url = '/story/hansel-gretel/illustrations/hg-art-08-candy-house-reveal.jpg',
    description = '길을 잃은 남매가 서로에게 묻고 답하며 숲을 되돌아 나오는 이야기예요.',
    category = '고전동화'
where id = 'HG';
