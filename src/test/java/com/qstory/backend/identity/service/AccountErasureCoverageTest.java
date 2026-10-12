package com.qstory.backend.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 회원 탈퇴가 놓치는 테이블이 없게(보안 점검 M3) - db/schema의 마이그레이션을 순서대로 읽어 app_user를 가리키는 컬럼(지금 남아 있는
 * 테이블)을 모두 찾고, 아래 목록과 같은지 본다. app_user 행은 탈퇴 때 지우지 않고 익명화하므로 FK의 on delete cascade/set null이
 * 돌지 않는다 - 새 컬럼이 생기면 AccountErasureService에서 지우거나 비우고, 남겨도 되는 이유와 함께 여기에 적는다.
 */
class AccountErasureCoverageTest {

    /** 테이블.컬럼 → 탈퇴 때 어떻게 되는지. */
    private static final Map<String, String> HANDLED = Map.ofEntries(
            Map.entry("story_revision.author_id", "보존 - 내부(STAFF) 작품 편집 이력. 개인 정보 없음, 계정은 익명화"),
            Map.entry("password_reset_token.user_id", "삭제"),
            Map.entry("story_completion.user_id", "보호자 가정 기록·선생님 개인 기록 삭제, 기관 반 수업 기록은 보존"),
            Map.entry("tutor_student.tutor_id", "개인 반 학생 삭제, 기관 반 학생은 담임만 비움"),
            Map.entry("tutor_student.linked_parent_user_id", "연결 해제"),
            Map.entry("improvement_feedback.user_id", "삭제"),
            Map.entry("account_deletion_feedback.user_id", "보존 - 탈퇴 사유"),
            Map.entry("parent_child.parent_id", "아이 이름 마스킹"),
            Map.entry("notification_settings.user_id", "삭제"),
            Map.entry("bookmark.user_id", "삭제"),
            Map.entry("organization_tutor.tutor_id", "삭제(기관 소속 해제)"),
            Map.entry("organization_tutor_invite.used_by_tutor_id", "보존 - 초대 사용 이력, 계정은 익명화"),
            Map.entry("lesson.tutor_id", "개인 수업 삭제, 기관 반 수업은 보존"),
            Map.entry("notifications.user_id", "삭제"),
            Map.entry("payment_order.user_id", "보존 - 결제 기록 5년"),
            Map.entry("class_group.tutor_id", "개인 반 삭제, 기관 반은 담임 해제"),
            Map.entry("voice_research_preferences.user_id", "보호자는 철회 기록, 그 밖의 역할은 삭제"),
            Map.entry("voice_research_consents.user_id", "보호자 철회 때 녹음과 함께 삭제"),
            Map.entry("story_completion_participant.parent_user_id", "비움"),
            Map.entry("class_homeroom_history.tutor_id", "개인 반 이력 삭제, 기관 반 담임 이력은 보존"),
            Map.entry("user_consent.user_id", "보존 - 동의 이력"),
            Map.entry("play_session.user_id", "남은 기록(기관 반 수업)이 가리키지 않는 회차와 대화 줄 삭제"),
            Map.entry("story_sessions.user_id", "계정 연결만 비움(익명 통계로 남김)"),
            Map.entry("interaction_event.user_id", "삭제"),
            Map.entry("session_recording_chunk.user_id", "삭제"),
            Map.entry("screen_recording_consent.user_id", "삭제"),
            Map.entry("screen_recording_consent.decided_by", "보존 - 수업 녹화 동의를 확인한 선생님 기록, 계정은 익명화"),
            Map.entry("class_homeroom_invite.created_by", "보존 - 담임 초대 이력, 계정은 익명화"),
            Map.entry("class_homeroom_invite.used_by", "보존 - 담임 초대 이력, 계정은 익명화"),
            Map.entry("push_device_token.user_id", "삭제"),
            Map.entry("class_group.archived_by", "보존 - 반 보관 이력, 계정은 익명화"),
            Map.entry("tutor_student_class_history.changed_by", "보존 - 반 이동 이력, 계정은 익명화"),
            Map.entry("organization.guardian_consent_ack_by", "보존 - 기관 보호자 동의 확인 기록, 계정은 익명화"));

    private static final Pattern CREATE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(?:public\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER = Pattern.compile(
            "alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?(?:public\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP = Pattern.compile(
            "drop\\s+table\\s+(?:if\\s+exists\\s+)?(?:public\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCES_APP_USER =
            Pattern.compile("references\\s+(?:public\\.)?app_user\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOREIGN_KEY = Pattern.compile("foreign\\s+key\\s*\\((\\w+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_COLUMN =
            Pattern.compile("add\\s+column\\s+(?:if\\s+not\\s+exists\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_DEF = Pattern.compile("^\\s*(\\w+)\\s+uuid\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void everyColumnReferencingAppUserIsHandledByErasure() throws IOException {
        assertEquals(new TreeSet<>(HANDLED.keySet()), new TreeSet<>(columnsReferencingAppUser()),
                "app_user를 가리키는 컬럼이 바뀌었어요 - AccountErasureService에서 처리하고 HANDLED에 적어 주세요.");
    }

    static Set<String> columnsReferencingAppUser() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/schema/*.sql");
        Arrays.sort(files, Comparator.comparing(Resource::getFilename));
        Set<String> columns = new LinkedHashSet<>();
        for (Resource file : files) {
            String table = null;
            for (String line : read(file).split("\n")) {
                String code = line.replaceFirst("--.*$", "");
                Matcher drop = DROP.matcher(code);
                if (drop.find()) {
                    String dropped = drop.group(1).toLowerCase();
                    columns.removeIf(column -> column.startsWith(dropped + "."));
                    continue;
                }
                Matcher create = CREATE.matcher(code);
                Matcher alter = ALTER.matcher(code);
                if (create.find()) {
                    table = create.group(1).toLowerCase();
                } else if (alter.find()) {
                    table = alter.group(1).toLowerCase();
                }
                if (!REFERENCES_APP_USER.matcher(code).find()) continue;
                String column = column(code);
                if (table == null || column == null) {
                    throw new AssertionError("app_user 참조를 해석하지 못했어요: " + file.getFilename() + ": " + line);
                }
                columns.add(table + "." + column.toLowerCase());
            }
        }
        return columns;
    }

    private static String column(String line) {
        for (Pattern pattern : new Pattern[] {FOREIGN_KEY, ADD_COLUMN, COLUMN_DEF}) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    private static String read(Resource file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
