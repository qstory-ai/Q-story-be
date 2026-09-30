-- 선생님 기능을 반 중심으로 단순화한다(Q-28).
--
-- 1:1 과외도 학생 한 명짜리 반으로 다룬다. 선생님이 학생을 한 명씩 등록하고 학생마다 부모 초대를 보내던 기능,
-- 학생별로 이야기를 담아 두던 수업 계획, 이미 코드에서 빠진 요일 일정표를 없앤다. 학생은 학부모가 반 초대 링크로
-- 아이를 연결할 때 반 명단에 올라간다. 운영 데이터가 없는 상태에서 정리한다.
drop table if exists public.tutor_invite;
drop table if exists public.tutor_lesson_plan;
drop table if exists public.tutor_schedule;
