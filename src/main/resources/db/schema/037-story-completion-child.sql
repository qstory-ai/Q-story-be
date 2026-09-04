-- 완주 기록을 아이별로 필터할 수 있게 story_completion에 nullable child_id 컬럼을 추가한다.
-- IA "종합 리포트"가 "아이별 흐름"을 요구하는데 지금까지는 계정 전체 완주가 섞여 있었다.
-- nullable로 두는 이유:
--   1) 기존 완주 기록에는 childId가 없다 - 그 시절엔 app_user.child_name 단일 문자열이 유일한
--      아이 표현이었고 아이 프로필도 없었다. 이후 발급된 새 기록만 이 컬럼을 채운다.
--   2) 선생님이 진행한 세션(tutor_student_id가 있는 행)은 부모 아이 프로필과 무관하므로
--      child_id가 없다 - 이 조합은 그대로 유효.
--   3) 아이 프로필이 삭제되면(parent_child on delete cascade) 관련 완주 기록의 child_id는
--      null로 남겨야 리포트가 조용히 사라지지 않는다 - set null.
alter table public.story_completion
    add column if not exists child_id uuid references public.parent_child(id) on delete set null;

create index if not exists story_completion_user_child_idx
    on public.story_completion (user_id, child_id, completed_at desc);
