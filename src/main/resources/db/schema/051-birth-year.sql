-- 아이 나이를 "N세"/연령대 구간으로 직접 받지 않고 출생연도(~년생)로 받아 계산한다(ChildAge).
-- age_band 컬럼은 남긴다 - 출생연도가 있으면 서버가 매번 여기서 계산해 채우고, 출생연도가 없는
-- 예전 행은 저장된 age_band를 그대로 쓴다. 그래서 두 컬럼 다 nullable이 아니라 birth_year만
-- nullable이다.
alter table public.parent_child add column if not exists birth_year integer;
alter table public.tutor_student add column if not exists birth_year integer;
