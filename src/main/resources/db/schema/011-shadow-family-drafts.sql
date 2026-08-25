-- ShadowFamilyGenerationService의 산출물 - shadow_intent_candidates(사람이 이미 승인한 반복 질문)
-- 하나당 미리 생성해 둔 15번째 family 초안(대본/삽화/오디오 참조) 하나. review_status는 사람 검수
-- 없이 자동 LLM 게이트만 통과하면 바로 approved로 저장된다(제품 결정, ShadowFamilyGenerationService
-- 참고) - shadow_intent_candidates.review_status(사람이 반드시 승인/거절해야 하는 후보 자체의
-- 상태)와는 별개다.
create table if not exists public.shadow_family_drafts (
    id uuid primary key,
    candidate_id uuid not null unique references public.shadow_intent_candidates on delete cascade,
    proposed_family_id text not null,
    title text not null,
    intent_summary varchar(160) not null,
    rationale varchar(300) not null,
    acknowledgement_text varchar(120) not null,
    entry_state varchar(200) not null,
    exit_state varchar(200) not null,
    rejoin_anchor_id text not null,
    report_summary varchar(220) not null,
    choice_copy jsonb not null,
    beats jsonb not null,
    image_brief jsonb not null,
    image_object_name text not null,
    audio_object_name text not null,
    image_mime_type text not null,
    audio_mime_type text not null,
    review_status varchar(255) not null,
    prompt_version text not null,
    llm_model text not null,
    image_model text not null,
    tts_model text not null,
    generated_at timestamptz not null,
    reviewed_at timestamptz,
    review_note varchar(1000)
);

create index if not exists shadow_family_drafts_review_status_idx
    on public.shadow_family_drafts (review_status, generated_at desc);
