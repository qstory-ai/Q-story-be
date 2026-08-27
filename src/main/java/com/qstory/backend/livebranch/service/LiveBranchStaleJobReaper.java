package com.qstory.backend.livebranch.service;

import com.qstory.backend.common.enums.LiveBranchJobStatus;
import com.qstory.backend.livebranch.entity.LiveBranchJob;
import com.qstory.backend.livebranch.repository.LiveBranchJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앱 재시작/배포 중단이나 liveBranchExecutor 큐 포화(기본 AbortPolicy로 조용히 유실될 수 있음)로
 * QUEUED/GENERATING에 멈춰버린 job을 정리한다 - 그렇지 않으면 프런트의 로딩 패널이 영원히 폴링만
 * 하게 된다. BetaSessionRetentionScheduler와 같은 패턴의 @Scheduled 정리 작업이다.
 */
@Component
public class LiveBranchStaleJobReaper {

    private static final Logger log = LoggerFactory.getLogger(LiveBranchStaleJobReaper.class);
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private final LiveBranchJobRepository repository;

    public LiveBranchStaleJobReaper(LiveBranchJobRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Transactional
    public void reapStaleJobs() {
        Instant cutoff = Instant.now().minus(STALE_AFTER);
        List<LiveBranchJob> stale = repository.findByStatusInAndUpdatedAtBefore(
                List.of(LiveBranchJobStatus.QUEUED, LiveBranchJobStatus.GENERATING), cutoff);
        if (stale.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (LiveBranchJob job : stale) {
            job.setStatus(LiveBranchJobStatus.FAILED);
            job.setErrorCode("STALE_ORPHANED");
            job.setUpdatedAt(now);
        }
        repository.saveAll(stale);
        log.info("live-branch-stale-job-reaper.reaped count={}", stale.size());
    }
}
