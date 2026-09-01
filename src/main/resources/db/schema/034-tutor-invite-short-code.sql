-- tutor_invite에 사람이 손으로 옮길 수 있는 짧은 코드를 추가한다. 기존 URL(token) 방식은
-- 그대로 유지하고, 부모가 링크를 열 수 없거나 선생님이 구두로 전달할 때 이 코드를 쓴다.
-- ClassGroup.joinCode와 같은 알파벳(0/O/1/I/L 제외)의 8자 문자열이라, JoinCodeGenerator를
-- 그대로 재사용한다.
--
-- nullable로 추가하는 이유: 기존 초대 행(발급 이력)에는 short_code가 없다. 새 초대부터만
-- 반드시 함께 생성한다(BE 서비스에서 not-null을 보장).
alter table public.tutor_invite
    add column if not exists short_code varchar(16);

create unique index if not exists tutor_invite_short_code_uidx
    on public.tutor_invite (short_code)
    where short_code is not null;
