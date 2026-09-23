-- 043/044/045가 만들던 세 테이블(독서 토론 주제, 언어 규칙, 삽화 생성 근거)을 제거한다.
-- StoryImportService만 쓰고(personas.yaml 등과 함께 임포트) 런타임에서 읽는 서비스가 하나도
-- 없었다. 원본은 여전히 fe/q-story-web/content/의 discussion-bank.yaml, language-rules.yaml,
-- visual-provenance.yaml에 저작 문서로 남아 있고, 나중에 실제로 읽는 기능이 생기면 그때 전용
-- 테이블을 다시 만든다. 043~045 파일 자체도 schema-locations에서 뺐으므로 새 DB에는 애초에
-- 생성되지 않으며, 이 파일은 기존 DB를 정리하는 용도다(if exists라 매 부팅 멱등).
drop table if exists public.story_discussion_topic;
drop table if exists public.language_policy;
drop table if exists public.story_visual_provenance;
