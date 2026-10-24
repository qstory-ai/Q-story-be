-- 그라파나 "사용자 응답" 대시보드 고도화(10/24). 출시 알림·1분 후기·가입·탈퇴·협업 문의를 문항별로 세는 뷰와,
-- 내부 테스트 입력을 가려내는 표시(is_test)를 둔다. 지금까지 쌓인 응답의 절반 넘게가 팀 QA·자동화 테스트라
-- 실제 반응을 보려면 빼고 봐야 한다. 판정은 글자 모양으로만 한다(테스트·QA·가상·자동화 같은 낱말, example·.invalid·.test
-- 메일, 숫자 없는 나이 등) - 놓치거나 잘못 잡은 건은 이 함수를 고친다.

create or replace function grafana.looks_like_test(texts text[], email text)
returns boolean language sql immutable as $fn$
    select coalesce(array_to_string(texts, ' '), '') ~* '(테스트|test|qa|가상|검토용|자동화|automated|dummy|더미|샘플|sample|시드|seed|e2e)'
        or coalesce(email, '') ~* '(@example\.|\.invalid$|\.test$|@qstory\.)';
$fn$;

-- 출시 알림 신청 - 나이는 자유 입력이라 나이대로 묶고, 알게 된 경로도 몇 갈래로 묶는다.
create or replace view grafana.launch_requests as
select
    created_at,
    parent_name,
    email,
    phone,
    child_age,
    case
        when child_age ~* '(개월|month)' then '0~2세'
        when substring(child_age from '[0-9]+')::int <= 2 then '0~2세'
        when substring(child_age from '[0-9]+')::int <= 4 then '3~4세'
        when substring(child_age from '[0-9]+')::int <= 7 then substring(child_age from '[0-9]+') || '세'
        when substring(child_age from '[0-9]+')::int <= 13 then '8세 이상'
        else '알 수 없음'
    end as age_group,
    case child_gender when 'GIRL' then '여아' when 'BOY' then '남아' else '밝히지 않음' end as gender,
    discovery_source,
    case
        when discovery_source ~* '(인스타|insta)' then '인스타그램'
        when discovery_source ~* '(지인|소개|추천|친구|대표)' then '지인·소개'
        when discovery_source ~* '(블로그|네이버|naver|검색|google|구글)' then '블로그·검색'
        when discovery_source ~* '(카페|맘|커뮤니티|카카오|kakao|오픈채팅|단톡)' then '커뮤니티'
        when discovery_source ~* '(유치원|어린이집|학원|선생|기관|학교)' then '기관·선생님'
        when discovery_source ~* '(qr|전단|포스터|행사|부스|박람회)' then '오프라인·QR'
        else '기타'
    end as source_group,
    wants_contact,
    grafana.looks_like_test(array[parent_name, child_age, discovery_source], email)
        or child_age !~ '[0-9]' or length(trim(coalesce(discovery_source, ''))) < 2
        or discovery_source ~ '^[0-9 .x]+$' as is_test
from public.launch_notification_requests;

-- 1분 체험 후기 - 문항 답 그대로(체크박스 문항은 jsonb 배열).
create or replace view grafana.survey_answers as
select
    created_at, story_id, child_age_band, child_engagement, input_understanding, help_needed, child_reactions,
    disruptions, report_helpfulness, best_aspect, top_priority, retry_interest, one_line_review, review_usage_consent,
    wants_next_stories, contact, contact_consent,
    grafana.looks_like_test(array[one_line_review, top_priority, best_aspect], contact) as is_test
from public.completion_surveys;

-- 가입 계정 - 연락처 없이 역할·탈퇴 여부만.
create or replace view grafana.signups as
select
    created_at,
    case role when 'PARENT' then '보호자' when 'TUTOR' then '선생님' when 'DIRECTOR' then '원장' else role end as role,
    deleted_at is not null as deleted,
    role not in ('PARENT', 'TUTOR', 'DIRECTOR')
        or grafana.looks_like_test(array[login_id, display_name, child_name], email) as is_test
from public.app_user;

create or replace view grafana.account_deletions as
select
    created_at,
    case role when 'PARENT' then '보호자' when 'TUTOR' then '선생님' when 'DIRECTOR' then '원장' else role end as role,
    coalesce(nullif(reason_category, ''), '기타') as reason
from public.account_deletion_feedback;

create or replace view grafana.partnership_list as
select
    created_at,
    case role when 'ORGANIZATION' then '교육기관' when 'PUBLISHER' then '출판사·콘텐츠' else '기타' end as kind,
    affiliation, job_title, name, email, phone, message,
    grafana.looks_like_test(array[name, affiliation, message], email) as is_test
from public.partnership_inquiries;

-- 전체 응답 목록(091·092)에도 같은 테스트 표시를 뒤에 붙인다.
create or replace view grafana.user_responses as
select created_at as occurred_at, '출시 알림 신청'::text as kind,
       concat_ws(' · ', nullif(child_age, ''), nullif(discovery_source, '')) as summary, parent_name as name, email, phone, case when wants_contact then '동의' else '미동의' end as contact_consent,
       grafana.looks_like_test(array[parent_name, child_age, discovery_source], email) or child_age !~ '[0-9]'
           or length(trim(coalesce(discovery_source, ''))) < 2 or discovery_source ~ '^[0-9 .x]+$' as is_test
from public.launch_notification_requests
union all
select created_at, '가입',
       case role when 'PARENT' then '보호자' when 'TUTOR' then '선생님' when 'DIRECTOR' then '원장' else role end, display_name, email, null, null,
       grafana.looks_like_test(array[login_id, display_name, child_name], email)
from public.app_user
where role in ('PARENT', 'TUTOR', 'DIRECTOR')
union all
select created_at, '1분 후기',
       concat_ws(' · ', nullif(child_age_band, ''), '몰입 ' || child_engagement, '리포트 ' || report_helpfulness,
                 left(regexp_replace(regexp_replace(coalesce(nullif(one_line_review, ''), nullif(top_priority, ''), ''),
                     '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
                     '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120)), null,
       case when contact like '%@%' then contact end, case when contact not like '%@%' then nullif(contact, '') end, contact_consent,
       grafana.looks_like_test(array[one_line_review, top_priority, best_aspect], contact)
from public.completion_surveys
union all
select created_at, '협업 문의',
       concat_ws(' · ', nullif(affiliation, ''), nullif(role, ''),
                 left(regexp_replace(regexp_replace(coalesce(message, ''),
                     '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
                     '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120)), name, email, phone, '문의(연락 요청)',
       grafana.looks_like_test(array[name, affiliation, message], email)
from public.partnership_inquiries
union all
select f.created_at, case when f.message like '[오류 제보]%' then '오류 제보' else '기능 제안' end,
       left(regexp_replace(regexp_replace(f.message,
           '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
           '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120),
       u.display_name, u.email, null, null,
       grafana.looks_like_test(array[u.login_id, u.display_name], u.email)
from public.improvement_feedback f
left join public.app_user u on u.id = f.user_id
union all
select created_at, '회원 탈퇴', null, null, null, null, null, false
from public.account_deletion_feedback;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on grafana.launch_requests, grafana.survey_answers, grafana.signups, grafana.account_deletions, grafana.partnership_list from anon';
        execute 'revoke all on function grafana.looks_like_test(text[], text) from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on grafana.launch_requests, grafana.survey_answers, grafana.signups, grafana.account_deletions, grafana.partnership_list from authenticated';
        execute 'revoke all on function grafana.looks_like_test(text[], text) from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant select on grafana.launch_requests, grafana.survey_answers, grafana.signups, grafana.account_deletions, grafana.partnership_list to grafana_reader';
        execute 'grant execute on function grafana.looks_like_test(text[], text) to grafana_reader';
    end if;
end
$$;
