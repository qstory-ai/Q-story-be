-- 수업 화면 녹화 동의(source=LESSON)를 누가 어느 수업에서 확인했는지 남긴다(보안 점검 M5). 지금까지 LESSON 동의는 로그인 없이
-- 누구나 남길 수 있었다 - 이제 수업을 진행하는 선생님(또는 원장)만 남기고, 그 계정과 수업을 함께 적는다.
-- 동의 행은 베타 세션 단위 그대로(user_id 없음)라 기존 유니크 인덱스(베타 세션당 한 행)는 바뀌지 않는다.
alter table public.screen_recording_consent
    add column if not exists decided_by uuid references public.app_user(id) on delete set null;
alter table public.screen_recording_consent
    add column if not exists lesson_id uuid references public.lesson(id) on delete set null;
