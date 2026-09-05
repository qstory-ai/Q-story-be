-- 정기 수업을 만들면 프런트가 날짜마다 POST /v1/tutor-lessons를 반복 호출해 최대 60개의 개별
-- lesson 행을 만드는데, 지금까지는 그 행들을 하나의 "시리즈"로 묶는 방법이 전혀 없었다 -
-- 그래서 "이 수업만 수정" vs "향후 모든 수업 수정"을 구분할 수가 없었다(각 lesson이 서로
-- 완전히 독립된 행이라 어느 게 같은 시리즈인지 서버가 알 방법이 없었음).
--
-- series_id는 서버가 생성하지 않는다 - 프런트가 한 번의 정기 수업 제출(N번의 POST 호출)
-- 시작 시 crypto.randomUUID()로 하나 만들어서 N번 모두에 실어 보낸다. 서버는 그저 같은 값을
-- 저장할 뿐이고, 같은 tutor_id 아래에서만 유효하다고 취급한다(LessonService가 소유권을
-- 검증). 단발성 수업은 null로 남는다.
alter table public.lesson
    add column if not exists series_id uuid;

create index if not exists lesson_series_idx on public.lesson (series_id);
