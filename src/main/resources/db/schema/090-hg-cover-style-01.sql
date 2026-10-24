-- 헨젤과 그레텔 카탈로그 커버를 스타일 01 삽화로. 10/21에 삽화 48장을 스타일 01(-s1.webp)로 바꿨는데
-- 커버(006에서 넣은 옛 jpg 경로)만 남아 라이브러리·상세·부모 홈 썸네일이 옛 그림으로 보였다.
-- 경로 형식은 006과 같은 "/story/<slug>/..."로 두면 StoryAssetUrls.forCover()가 삽화 버킷 URL로 바꿔 준다.
update public.story
set cover_image_url = '/story/hansel-gretel/illustrations/hg-art-08-candy-house-reveal-s1.webp'
where id = 'HG';
