-- 그라파나 "사용자 응답" 대시보드용 뷰. 랜딩 출시 알림 신청·가입·1분 후기·협업 문의·마이페이지 요청·탈퇴 사유를
-- 한곳에서 날짜별로 세고 최근 목록을 본다. grafana_reader는 grafana 스키마만 읽으므로 원본 테이블 대신 이 뷰를 준다.
-- 이름·이메일·전화번호·연락처 칸은 고르지 않고, 자유 글은 이메일·휴대전화 모양을 가린 앞 120자만 넣는다.

create or replace view grafana.user_responses as
select created_at as occurred_at, '출시 알림 신청'::text as kind,
       concat_ws(' · ', nullif(child_age, ''), nullif(discovery_source, '')) as summary
from public.launch_notification_requests
union all
select created_at, '가입',
       case role when 'PARENT' then '보호자' when 'TUTOR' then '선생님' when 'DIRECTOR' then '원장' else role end
from public.app_user
where role in ('PARENT', 'TUTOR', 'DIRECTOR')
union all
select created_at, '1분 후기',
       concat_ws(' · ', nullif(child_age_band, ''), '몰입 ' || child_engagement, '리포트 ' || report_helpfulness,
                 left(regexp_replace(regexp_replace(coalesce(nullif(one_line_review, ''), nullif(top_priority, ''), ''),
                     '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
                     '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120))
from public.completion_surveys
union all
select created_at, '협업 문의',
       concat_ws(' · ', nullif(affiliation, ''), nullif(role, ''),
                 left(regexp_replace(regexp_replace(coalesce(message, ''),
                     '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
                     '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120))
from public.partnership_inquiries
union all
select created_at, case when message like '[오류 제보]%' then '오류 제보' else '기능 제안' end,
       left(regexp_replace(regexp_replace(message,
           '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
           '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120)
from public.improvement_feedback
union all
select created_at, '회원 탈퇴', null
from public.account_deletion_feedback;

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'anon') then
        execute 'revoke all on grafana.user_responses from anon';
    end if;
    if exists (select 1 from pg_roles where rolname = 'authenticated') then
        execute 'revoke all on grafana.user_responses from authenticated';
    end if;
    if exists (select 1 from pg_roles where rolname = 'grafana_reader') then
        execute 'grant select on grafana.user_responses to grafana_reader';
    end if;
end
$$;
