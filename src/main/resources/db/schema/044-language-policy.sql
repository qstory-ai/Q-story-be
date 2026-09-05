-- 스토리 제작 규약 §2.4 "아이의 언어 규칙" - scope='GLOBAL' 한 행이 전 스토리 공용으로 쓰인다.
-- Story에 대한 FK가 없다: GLOBAL은 어떤 스토리도 가리키지 않는다(LanguagePolicy.java 참고).
create table if not exists public.language_policy (
    scope varchar(255) not null,
    max_sentence_length integer not null,
    recommended_min integer not null,
    recommended_max integer not null,
    onomatopoeia_policy varchar(300) not null,
    banned_words jsonb not null,
    primary key (scope)
);
