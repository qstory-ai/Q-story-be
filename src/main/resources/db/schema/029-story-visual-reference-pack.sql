-- Phase 2 §4: 이미지 생성 프롬프트에 텍스트로 덧붙이는 캐릭터/장소/소품/스타일 불변 사실
-- (LiveBranchExecutionWorker.buildImagePrompt/StoryVisualReferencePack.java 참고). 참조 이미지
-- 한 장에만 의존하지 않고 텍스트 규칙으로도 인물 외형 일관성을 이중 보강한다.
--
-- story_id에는 의도적으로 FK 제약을 걸지 않는다(이 테이블 하나만 예외): 이 마이그레이션은 매 부팅마다
-- 실행되는데(mode: always), 스토리가 아직 한 번도 임포트되지 않은 새 환경(CI/새 개발 환경)에서
-- story 테이블에 해당 행이 없으면 FK 위반으로 전체 부팅이 막혀버린다. 아래 insert 자체도 story
-- 존재 여부를 where exists로 guard해서, 스토리가 아직 없으면 조용히 건너뛰고 스토리가 임포트된
-- 이후의 재부팅 때 자연스럽게 채워지도록 했다.
create table if not exists public.story_visual_reference_pack (
    id varchar(255) not null,
    story_id varchar(255) not null,
    kind varchar(32) not null check (kind in ('CHARACTER', 'LOCATION', 'PROP', 'STYLE')),
    label varchar(255) not null,
    immutable_facts jsonb not null,
    primary key (id)
);

create index if not exists story_visual_reference_pack_story_label_idx
    on public.story_visual_reference_pack (story_id, label);

-- 프론트 콘텐츠 빌드 파이프라인이 이제 packageData.visualReferencePacks를 정식으로 만들어 보낸다
-- (Phase 3) - 그래도 이 시드는 지우지 않고 남겨 둔다: 이 마이그레이션은 매 부팅마다 실행되는데
-- (mode: always), 스토리가 한 번도 임포트된 적 없는 새 환경(CI/새 개발 환경)의 부트스트랩 값으로만
-- 쓰기 위함이다. 그래서 아래는 on conflict do nothing이다 - do update였다면 실제 임포트로 들어온
-- 값을 다음 재부팅 때마다 이 스냅샷으로 조용히 되돌려버린다. do nothing이면 행이 비어 있을 때만
-- 채우고, 한 번이라도 정식 임포트가 일어난 뒤로는 이 insert가 완전히 무해해진다.
insert into public.story_visual_reference_pack (id, story_id, kind, label, immutable_facts)
select 'HG-VIS-HANSEL', 'HG', 'CHARACTER', 'HANSEL',
       '["그레텔보다 약간 큰 어린 남자아이", "짧은 갈색 머리, 초록색 조끼, 크림색 셔츠, 붉은 목수건", "영리하지만 어린아이의 취약함이 남아 있는 얼굴과 체격"]'::jsonb
where exists (select 1 from public.story where id = 'HG')
on conflict (id) do nothing;

insert into public.story_visual_reference_pack (id, story_id, kind, label, immutable_facts)
select 'HG-VIS-GRETEL', 'HG', 'CHARACTER', 'GRETEL',
       '["헨젤보다 약간 작은 어린 여자아이", "갈색 양갈래 땋은 머리와 보라색 리본, 겨자색 원피스", "따뜻하고 관찰력이 좋으며 탈출 장면에서는 결단력 있는 표정"]'::jsonb
where exists (select 1 from public.story where id = 'HG')
on conflict (id) do nothing;

-- OLD_WOMAN과 WITCH는 같은 인물이라 immutable_facts가 완전히 동일하다 - 이미지 생성 시점에 어느
-- 라벨로 불려도(imageBrief.characters가 "HG-SPK-OLD_WOMAN" 또는 "HG-SPK-WITCH" 어느 쪽이든) 같은
-- 얼굴 골격·키·체격 규칙이 적용되게 하기 위해 행을 두 개로 나눠 둔다(정규화된 label 값이 서로
-- 다르므로 하나의 행으로 합칠 수 없다).
insert into public.story_visual_reference_pack (id, story_id, kind, label, immutable_facts)
select 'HG-VIS-OLD-WOMAN', 'HG', 'CHARACTER', 'OLD_WOMAN',
       '["회색 머리를 뒤로 올린 동일한 노년 여성", "어두운 보라색 옷과 같은 얼굴 골격·키·체격을 유지", "노파일 때의 다정한 표정과 마녀일 때의 차가운 표정만 달라짐"]'::jsonb
where exists (select 1 from public.story where id = 'HG')
on conflict (id) do nothing;

insert into public.story_visual_reference_pack (id, story_id, kind, label, immutable_facts)
select 'HG-VIS-WITCH', 'HG', 'CHARACTER', 'WITCH',
       '["회색 머리를 뒤로 올린 동일한 노년 여성", "어두운 보라색 옷과 같은 얼굴 골격·키·체격을 유지", "노파일 때의 다정한 표정과 마녀일 때의 차가운 표정만 달라짐"]'::jsonb
where exists (select 1 from public.story where id = 'HG')
on conflict (id) do nothing;
