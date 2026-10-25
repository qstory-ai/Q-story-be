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
 * 데모 회차 리포트 분석 작업(demo_report_analysis, 097). 데모 리포트를 열면 PENDING으로 넣고 DemoReportAnalysisWorker가
 * 가져가 만든다. 결과 모양은 ReportAnalysisStore.find와 같다 - 프런트는 로그인 리포트와 같은 화면으로 그린다.
 */
@Component
public class DemoReportAnalysisStore {

    /** 실패한 분석을 다시 넣을 수 있는 횟수. */
    static final int MAX_ATTEMPTS = 3;
    /** 리포트를 열자마자 가져가지 않는다 - 마지막 대화 줄이 저장될 틈. */
    static final int SETTLE_SECONDS = 3;

    static final String ENQUEUE = """
            insert into demo_report_analysis (session_id, story_id, read_from_scene_id, read_through_scene_id, status, attempts,
                created_at, updated_at)
            values (?, ?, ?, ?, 'PENDING', 0, ?, ?)
            on conflict (session_id) do update set status = 'PENDING', error = null, updated_at = excluded.updated_at,
                read_through_scene_id = excluded.read_through_scene_id
            where demo_report_analysis.status = 'FAILED' and demo_report_analysis.attempts < ?
            """;

    static final String CLAIM = """
            update demo_report_analysis set status = 'RUNNING', attempts = attempts + 1, updated_at = ?
            where session_id in (select session_id from demo_report_analysis
                where (status = 'PENDING' and updated_at < ?) or (status = 'RUNNING' and updated_at < ?)
                order by updated_at limit ? for update skip locked)
            returning session_id
            """;

    public record Job(UUID sessionId, String storyId, String readFromSceneId, String readThroughSceneId) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DemoReportAnalysisStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 처음이면 넣고, 실패했으면(횟수 안에서) 다시 넣는다. 이미 만들었거나 만드는 중이면 그대로 둔다. */
    @Transactional
    public void enqueue(UUID sessionId, String storyId, String readFromSceneId, String readThroughSceneId) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(ENQUEUE, sessionId, storyId, readFromSceneId, readThroughSceneId, now, now, MAX_ATTEMPTS);
    }

    @Transactional
    public List<UUID> claim(int limit) {
        Instant now = Instant.now();
        return jdbc.queryForList(CLAIM, UUID.class, Timestamp.from(now), Timestamp.from(now.minusSeconds(SETTLE_SECONDS)),
                Timestamp.from(now.minusSeconds(ReportAnalysisStore.STALE_RUNNING_MINUTES * 60L)), limit);
    }

    @Transactional(readOnly = true)
    public Job job(UUID sessionId) {
        List<Job> rows = jdbc.query(
                "select session_id, story_id, read_from_scene_id, read_through_scene_id from demo_report_analysis where session_id = ?",
                (rs, rowNum) -> new Job(rs.getObject("session_id", UUID.class), rs.getString("story_id"),
                        rs.getString("read_from_scene_id"), rs.getString("read_through_scene_id")),
                sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public void finish(UUID sessionId, String status, String modelId, String promptVersion, Map<String, Object> result, String error) {
        jdbc.update(
                "update demo_report_analysis set status = ?, model_id = ?, prompt_version = ?, result = cast(? as jsonb), error = ?, "
                        + "updated_at = ? where session_id = ?",
                status, modelId, promptVersion, result == null ? null : write(result),
                error == null ? null : error.substring(0, Math.min(error.length(), 500)),
                Timestamp.from(Instant.now()), sessionId);
    }

    /** {status, observations, cards, commonScenes} - ReportAnalysisStore.find와 같은 모양. 작업이 없으면 null. */
    @Transactional(readOnly = true)
    public Map<String, Object> find(UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.query(
                "select status, model_id, prompt_version, result from demo_report_analysis where session_id = ?",
                (rs, rowNum) -> {
                    Map<String, Object> analysis = new LinkedHashMap<>();
                    String status = rs.getString("status");
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
                sessionId);
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
