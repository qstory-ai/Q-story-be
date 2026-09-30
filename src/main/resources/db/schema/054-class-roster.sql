-- 기관 반도 학생 명단 구조로 통합한다: 기관 → 담임 선생님(=반) → 학생. 반 계정(CLASS_ACCOUNT)이라는 공용
-- 로그인은 없애고, 원장이 만든 반의 부모(app_user.class_group_id)는 tutor_student(학생)로 옮긴다.
-- 모든 문장은 멱등이다(스크립트가 부팅마다 다시 실행된다) - 이관할 행이 없으면 아무 일도 하지 않는다.
-- (Spring 스크립트 러너가 ;로 문장을 자르므로 DO 블록은 쓰지 않는다.)

-- 1) 담임이 아직 없는 기관 반의 학생은 tutor 없이 명단에만 올라간다. 담임이 배정되면 ClassService가 채운다.
alter table public.tutor_student alter column tutor_id drop not null;

-- 2) 반 계정은 소프트 삭제한다. 행을 지우면 story_completion.user_id의 on delete cascade로 그 반의 수업 기록이
--    함께 사라져 기관 리포트가 줄어든다. 역할 체크 제약에서 CLASS_ACCOUNT를 빼야 하므로 삭제된 행은 PARENT로
--    바꿔 둔다(로그인 불가: deleted_at이 있고 login_id가 바뀐다).
update public.app_user
   set deleted_at = coalesce(deleted_at, now()),
       login_id = 'deleted:' || id || ':' || login_id,
       role = 'PARENT',
       class_group_id = null,
       organization_id = null
 where role = 'CLASS_ACCOUNT';

alter table if exists public.app_user drop constraint if exists app_user_role_check;
alter table if exists public.app_user
    add constraint app_user_role_check check (role in ('DIRECTOR', 'PARENT', 'TUTOR', 'STAFF'));

-- 3) 반에 가입해 있던 부모 -> 학생 명단. 아이 프로필이 없으면 app_user.child_name 또는 "(부모 이름) 자녀"로
--    임시 프로필을 만든다(나이대는 6-7 기본값, 부모가 프로필에서 바꾼다).
insert into public.parent_child (id, parent_id, name, age_band, avatar_key, created_at, updated_at)
select gen_random_uuid(), u.id,
       left(coalesce(nullif(trim(u.child_name), ''), u.display_name || ' 자녀'), 40),
       '6-7', 'fox', now(), now()
  from public.app_user u
 where u.role = 'PARENT' and u.deleted_at is null and u.class_group_id is not null
   and not exists (select 1 from public.parent_child c where c.parent_id = u.id);

insert into public.tutor_student
       (id, tutor_id, name, age_band, birth_year, lesson_type, class_group_id, status, linked_parent_user_id, child_id, created_at)
select gen_random_uuid(), cg.tutor_id, left(child.name, 60),
       case when child.birth_year is not null
            then (extract(year from now())::int - child.birth_year)::text || '세'
            else child.age_band || '세' end,
       child.birth_year, 'CLASS', cg.id, 'CONFIRMED', u.id, child.id, now()
  from public.app_user u
  join public.class_group cg on cg.id = u.class_group_id
  join lateral (select p.id, p.name, p.age_band, p.birth_year
                  from public.parent_child p
                 where p.parent_id = u.id
                 order by p.created_at asc
                 limit 1) child on true
 where u.role = 'PARENT' and u.deleted_at is null
   and not exists (select 1 from public.tutor_student ts
                    where ts.linked_parent_user_id = u.id and ts.class_group_id = cg.id and ts.deleted_at is null)
on conflict do nothing;

-- 4) 부모 계정에서 기관·반 소속을 없앤다(이용권은 학생 명단에서 계산한다). app_user.class_group_id 컬럼은
--    이관이 확인된 뒤 별도 마이그레이션에서 지운다.
update public.app_user
   set class_group_id = null, organization_id = null
 where role = 'PARENT' and (class_group_id is not null or organization_id is not null);
