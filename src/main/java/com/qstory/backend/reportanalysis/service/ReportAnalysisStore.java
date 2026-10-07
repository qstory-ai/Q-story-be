package com.qstory.backend.reportanalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리포트 분석 작업(report_analysis, 069). 회차가 저장되면 PENDING으로 넣고 ReportAnalysisWorker가 가져가 만든다.
 * 같은 기록을 다시 넣으면(이어 읽기·다시 시도) PENDING으로 되돌린다.
 */
@Component
public class ReportAnalysisStore {

    /** 분석이 RUNNING인 채 이만큼 지나면(서버 재시작 등) 다시 가져간다. */
    static final int STALE_RUNNING_MINUTES = 10;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReportAnalysisStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueue(UUID completionId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(
                "insert into report_analysis (completion_id, status, attempts, created_at, updated_at) values (?, 'PENDING', 0, ?, ?) "
                        + "on conflict (completion_id) do update set status = 'PENDING', error = null, updated_at = excluded.updated_at",
                completionId, now, now);
    }

    /** 만들 차례인 작업을 가져가 RUNNING으로 표시한다 - 서버가 여러 대여도 같은 작업을 둘이 잡지 않는다. */
    @Transactional
    public List<UUID> claim(int limit) {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp stale = Timestamp.from(Instant.now().minusSeconds(STALE_RUNNING_MINUTES * 60L));
        return jdbc.queryForList(
                "update report_analysis set status = 'RUNNING', attempts = attempts + 1, updated_at = ? "
                        + "where completion_id in (select completion_id from report_analysis "
                        + "where status = 'PENDING' or (status = 'RUNNING' and updated_at < ?) "
                        + "order by updated_at limit ? for update skip locked) returning completion_id",
                UUID.class, now, stale, limit);
    }

    @Transactional
    public void finish(UUID completionId, String status, String modelId, String promptVersion, Map<String, Object> result, String error) {
        jdbc.update(
                "update report_analysis set status = ?, model_id = ?, prompt_version = ?, result = cast(? as jsonb), error = ?, updated_at = ? "
                        + "where completion_id = ?",
                status, modelId, promptVersion, result == null ? null : write(result),
                error == null ? null : error.substring(0, Math.min(error.length(), 500)),
                Timestamp.from(Instant.now()), completionId);
    }

    /** 리포트 상세용 - {status, modelId, promptVersion, observations, cards, commonScenes}. 작업이 없으면 null. */
    @Transactional(readOnly = true)
    public Map<String, Object> find(UUID completionId) {
        List<Map<String, Object>> rows = jdbc.query(
                "select status, model_id, prompt_version, result from report_analysis where completion_id = ?",
                (rs, rowNum) -> {
                    Map<String, Object> analysis = new LinkedHashMap<>();
                    String status = rs.getString("status");
                    // RUNNING은 프런트에서 보면 아직 만드는 중이라 PENDING과 같다.
                    analysis.put("status", "RUNNING".equals(status) ? "PENDING" : status);
                    analysis.put("modelId", rs.getString("model_id"));
                    analysis.put("promptVersion", rs.getString("prompt_version"));
                    String result = rs.getString("result");
                    Map<String, Object> parsed = result == null ? Map.of() : read(result);
                    analysis.put("observations", parsed.getOrDefault("observations", List.of()));
                    analysis.put("cards", parsed.getOrDefault("cards", List.of()));
                    analysis.put("commonScenes", parsed.getOrDefault("commonScenes", List.of()));
                    return analysis;
                },
                completionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String write(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception error) {
            return Map.of();
        }
    }
}
