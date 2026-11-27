-- 체험 모드(도서관 행사 등): 선생님이 현장에서 이름만 올린 학생에 보호자를 잇는 1회용 코드. 아이가 이야기를 마친 태블릿의
-- QR에만 실린다. 이 코드로 연결한 보호자는 연결 전에 남긴 체험 기록도 본다. 연결되면 null로 지운다.
alter table public.tutor_student add column if not exists parent_claim_code varchar(16);
create unique index if not exists ux_tutor_student_parent_claim_code
    on public.tutor_student (parent_claim_code) where parent_claim_code is not null;
