-- "아이디"(loginId)와 별개로 실제 연락용 이메일 주소를 받는다 - 지금까지 loginId가 이메일 형식을
-- 강제해서 이 둘이 사실상 같은 값이었는데, 이제 아이디는 자유 형식이고 이메일은 별도 컬럼이다.
-- unique 제약은 걸지 않는다 - 로그인 식별자가 아니라 연락용 필드다.
alter table public.app_user
    add column if not exists email varchar(255);
