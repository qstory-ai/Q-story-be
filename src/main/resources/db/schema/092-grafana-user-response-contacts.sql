-- 그라파나 "사용자 응답" 대시보드에서 연락처도 보이게(10/24 운영 요청). 091 뷰 뒤에 이름·이메일·전화·연락 동의 칸을 붙인다
-- (create or replace view는 칸을 뒤에만 더할 수 있다). 1분 후기 연락처는 한 칸이라 @가 있으면 이메일, 없으면 전화로 나눈다. 이 뷰를 읽는 grafana_reader 권한은 091에서 준 그대로다.
-- 연락 동의: 출시 알림은 wants_contact, 1분 후기는 contact_consent 답 그대로, 협업 문의는 연락을 요청한 글이라 따로 묻지 않는다.

create or replace view grafana.user_responses as
select created_at as occurred_at, '출시 알림 신청'::text as kind,
       concat_ws(' · ', nullif(child_age, ''), nullif(discovery_source, '')) as summary, parent_name as name, email, phone, case when wants_contact then '동의' else '미동의' end as contact_consent
from public.launch_notification_requests
union all
select created_at, '가입',
       case role when 'PARENT' then '보호자' when 'TUTOR' then '선생님' when 'DIRECTOR' then '원장' else role end, display_name, email, null, null
from public.app_user
where role in ('PARENT', 'TUTOR', 'DIRECTOR')
union all
select created_at, '1분 후기',
       concat_ws(' · ', nullif(child_age_band, ''), '몰입 ' || child_engagement, '리포트 ' || report_helpfulness,
                 left(regexp_replace(regexp_replace(coalesce(nullif(one_line_review, ''), nullif(top_priority, ''), ''),
                     '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
                     '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120)), null,
       case when contact like '%@%' then contact end, case when contact not like '%@%' then nullif(contact, '') end, contact_consent
from public.completion_surveys
union all
select created_at, '협업 문의',
       concat_ws(' · ', nullif(affiliation, ''), nullif(role, ''),
                 left(regexp_replace(regexp_replace(coalesce(message, ''),
                     '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
                     '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120)), name, email, phone, '문의(연락 요청)'
from public.partnership_inquiries
union all
select f.created_at, case when f.message like '[오류 제보]%' then '오류 제보' else '기능 제안' end,
       left(regexp_replace(regexp_replace(f.message,
           '[[:alnum:]._%+-]+@[[:alnum:].-]+\.[[:alpha:]]+', '(이메일)', 'g'),
           '01[0-9][- .]?[0-9]{3,4}[- .]?[0-9]{4}', '(전화번호)', 'g'), 120),
       u.display_name, u.email, null, null
from public.improvement_feedback f
left join public.app_user u on u.id = f.user_id
union all
select created_at, '회원 탈퇴', null, null, null, null, null
from public.account_deletion_feedback;
