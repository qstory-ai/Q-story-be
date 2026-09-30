-- 보호자 계정 단위 음성 연구 동의 - 마이페이지 "개인정보 및 데이터"에서 보고 끄고 다시 켤 수 있게 한다.
--
-- 지금까지 동의(voice_research_consents)는 이야기 세션마다 브라우저가 만드는 익명 행이었고, 철회는
-- 그 브라우저가 들고 있는 deletion_token으로만 가능했다. 서버는 "이 계정의 동의 상태"를 몰랐다.
--
-- 1) voice_research_preferences: 계정별 동의 상태. 행이 없으면 서버 기본값(VoiceResearchService.
--    DEFAULT_ENABLED)을 따른다 - notification_settings처럼 첫 변경 시점에 upsert한다.
create table if not exists public.voice_research_preferences (
    user_id uuid primary key references public.app_user(id) on delete cascade,
    enabled boolean not null,
    -- 마지막으로 동의한 약관 버전과 시각. 한 번도 켠 적이 없으면(기본값 상태에서 바로 끈 경우) null.
    consent_version varchar(255),
    consented_at timestamp(6) with time zone,
    -- 마지막으로 철회한 시각. 다시 동의하면 null로 되돌린다.
    withdrawn_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone not null
);

-- 2) 로그인한 보호자가 올린 녹음의 세션 동의에 계정을 남겨, 계정 단위 철회 때 그 녹음을 찾아 지울 수
--    있게 한다. 익명(비로그인) 세션과 이 컬럼이 생기기 전의 행은 null로 남는다(90일 보존 만료 정리 대상).
--    app_user는 소프트 삭제라 실제 행 삭제는 드물지만, 지워지더라도 녹음 행이 Storage 객체와 함께 보존
--    만료 정리를 받도록 cascade 대신 set null로 둔다.
alter table public.voice_research_consents
    add column if not exists user_id uuid references public.app_user(id) on delete set null;

create index if not exists voice_research_consents_user_idx
    on public.voice_research_consents (user_id);
