package com.qstory.backend.livebranch.repository;

import com.qstory.backend.common.enums.LiveBranchJobStatus;
import com.qstory.backend.livebranch.entity.LiveBranchJob;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveBranchJobRepository extends JpaRepository<LiveBranchJob, UUID> {

    /** LiveBranchStaleJobReaper가 앱 재시작/배포 중단이나 executor 큐 포화로 멈춰버린 작업을 찾는 데 쓴다. */
    List<LiveBranchJob> findByStatusInAndUpdatedAtBefore(List<LiveBranchJobStatus> statuses, Instant cutoff);
}
