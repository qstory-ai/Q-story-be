package com.qstory.backend.interaction.service;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화면 이용 기록(탭·스크롤·머무름) 수집 여부 - app_user.usage_tracking_enabled(073). 기본은 켜짐이고 로그인한 사용자가
 * 마이페이지에서 끈다. 비로그인 사용자의 끄기는 프런트가 보내지 않는 것으로 처리한다.
 */
@Service
public class UsageTrackingService {

    private final JdbcTemplate jdbc;

    public UsageTrackingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 계정 행이 없으면 기본값(켜짐). */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        List<Boolean> rows = jdbc.queryForList(
                "select usage_tracking_enabled from app_user where id = ?", Boolean.class, userId);
        return rows.isEmpty() || !Boolean.FALSE.equals(rows.get(0));
    }

    @Transactional
    public boolean setEnabled(UUID userId, boolean enabled) {
        jdbc.update("update app_user set usage_tracking_enabled = ? where id = ?", enabled, userId);
        return isEnabled(userId);
    }
}
