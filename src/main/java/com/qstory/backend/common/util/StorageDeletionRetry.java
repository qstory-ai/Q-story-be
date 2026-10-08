package com.qstory.backend.common.util;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 저장소 파일 삭제가 실패했을 때 다시 지운다(071, Q-41 후속). 탈퇴한 계정의 프로필 사진처럼 꼭 지워야 하는 파일만 넣는다.
 * 한 시간마다 다시 시도하고, 하루(24번)가 지나도 안 지워지면 에러 로그를 남겨 알림으로 사람이 보게 한다.
 */
@Component
public class StorageDeletionRetry {

    private static final Logger log = LoggerFactory.getLogger(StorageDeletionRetry.class);
    static final int MAX_ATTEMPTS = 24;

    private final JdbcTemplate jdbc;
    private final SupabaseStorageClient storageClient;

    public StorageDeletionRetry(JdbcTemplate jdbc, SupabaseStorageClient storageClient) {
        this.jdbc = jdbc;
        this.storageClient = storageClient;
    }

    public void enqueue(String bucket, String objectName) {
        jdbc.update("insert into storage_deletion_pending (bucket, object_name) values (?, ?) on conflict do nothing",
                bucket, objectName);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void retryPending() {
        List<String[]> pending = jdbc.query(
                "select bucket, object_name from storage_deletion_pending where attempts < ? order by created_at limit 100",
                (rs, row) -> new String[] {rs.getString("bucket"), rs.getString("object_name")}, MAX_ATTEMPTS);
        for (String[] item : pending) {
            String bucket = item[0];
            String objectName = item[1];
            if (storageClient.delete(bucket, objectName)) {
                jdbc.update("delete from storage_deletion_pending where bucket = ? and object_name = ?", bucket, objectName);
                log.info("storage-deletion-retry.deleted bucket={} object={}", bucket, objectName);
                continue;
            }
            Integer attempts = jdbc.queryForObject(
                    "update storage_deletion_pending set attempts = attempts + 1, last_attempt_at = now() "
                            + "where bucket = ? and object_name = ? returning attempts",
                    Integer.class, bucket, objectName);
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                log.error("storage-deletion-retry.gave-up bucket={} object={} attempts={}", bucket, objectName, attempts);
            }
        }
    }
}
