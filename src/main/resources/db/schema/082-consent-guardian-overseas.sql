-- 약관 2026-10-v2(공개 전 확인사항 A5·A9).
-- 보호자 법정대리인 확인(GUARDIAN)과 개인정보 국외 이전 동의(OVERSEAS_TRANSFER)는 user_consent에 새 유형으로 쌓으므로 표를 바꾸지 않는다.
-- 기관은 "원아 보호자 동의를 받아 이용한다"를 기관 단위로 확인한다 - 마지막 확인 시각·문구 버전·확인한 원장을 기관 행에 둔다.
-- 원장 계정은 탈퇴해도 익명화만 되므로(행 유지) 확인한 원장 기록은 남는다(AccountErasureCoverageTest).
alter table public.organization add column if not exists guardian_consent_ack_at timestamptz;
alter table public.organization add column if not exists guardian_consent_ack_version varchar(40);
alter table public.organization add column if not exists guardian_consent_ack_by uuid references public.app_user(id);
