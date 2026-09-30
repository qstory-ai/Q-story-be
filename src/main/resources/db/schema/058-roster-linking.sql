-- 학생 명단 후속 정리(PR 리뷰 반영). 멱등하게 작성한다.
--
-- 1) tutor_student.linked_at - 학부모가 연결된 시각. 기관 이용권 좌석을 이 순서로 채운다(등록 순서로 채우면
--    오래전에 등록만 해 둔 학생이 나중에 연결될 때 결제 기간 중인 다른 학부모의 좌석을 밀어냈다). 기존 연결은
--    정확한 시각을 모르므로 등록 시각으로 채운다.
alter table public.tutor_student add column if not exists linked_at timestamptz;
update public.tutor_student
   set linked_at = created_at
 where linked_parent_user_id is not null and linked_at is null;

-- 2) 담임이 없는 원장 반 중 명단의 선생님이 한 명뿐이고 그 선생님이 기관 소속이면 담임으로 채운다 - 원장 반에
--    학생을 넣어 수업하던 선생님이 054 이후 그 반을 볼 수 없게 되는 것을 막는다. 선생님이 여러 명인 반은
--    원장이 반 상세에서 직접 배정한다.
update public.class_group cg
   set tutor_id = t.tutor_id
  from (select class_group_id, (array_agg(tutor_id))[1] as tutor_id
          from public.tutor_student
         where tutor_id is not null and deleted_at is null and class_group_id is not null
         group by class_group_id
        having count(distinct tutor_id) = 1) t
 where cg.id = t.class_group_id
   and cg.tutor_id is null
   and cg.organization_id is not null
   and exists (select 1 from public.organization_tutor ot
                where ot.organization_id = cg.organization_id and ot.tutor_id = t.tutor_id);

-- 3) 담임이 생긴 반의 대기 학생(054로 이관된 학부모 등)을 담임의 학생으로 옮긴다. 담임에게 이미 같은 아이가
--    있으면((tutor_id, child_id) 유니크) 그 학생은 대기로 남긴다 - ClassService.assignHomeroom과 같은 규칙.
update public.tutor_student ts
   set tutor_id = cg.tutor_id
  from public.class_group cg
 where ts.class_group_id = cg.id
   and ts.tutor_id is null
   and ts.deleted_at is null
   and cg.tutor_id is not null
   and (ts.child_id is null
        or not exists (select 1 from public.tutor_student o
                        where o.tutor_id = cg.tutor_id and o.child_id = ts.child_id and o.deleted_at is null));
