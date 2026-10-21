package com.qstory.backend.realtimeimage.repository;

import com.qstory.backend.common.enums.RealtimeImageStatus;
import com.qstory.backend.realtimeimage.entity.RealtimeImage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RealtimeImageRepository extends JpaRepository<RealtimeImage, UUID> {

    /** 상한 계산 - 실패한 행은 빼고 센다(실패는 과금되지 않고, 다시 시도할 자리를 남겨 두기 위해). */
    long countBySessionIdAndAnchorIdAndStatusNot(UUID sessionId, String anchorId, RealtimeImageStatus status);
}
