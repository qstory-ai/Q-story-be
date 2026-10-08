-- Q-41 후속: 회원 탈퇴 때 프로필 사진 삭제가 실패하면 여기에 남겨 두고 StorageDeletionRetry가 다시 지운다.
-- 컬럼은 탈퇴 트랜잭션에서 이미 비워져 사진이 다시 노출되지는 않지만, 저장소의 파일 자체는 지워야 한다.
create table if not exists public.storage_deletion_pending (
    bucket varchar(120) not null,
    object_name varchar(500) not null,
    attempts integer not null default 0,
    created_at timestamptz not null default now(),
    last_attempt_at timestamptz,
    primary key (bucket, object_name)
);
