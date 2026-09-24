package com.qstory.backend.conversationrecord.service;

import com.qstory.backend.common.enums.ConversationInputMode;
import com.qstory.backend.common.enums.ConversationRecordKind;
import com.qstory.backend.conversationrecord.ConversationAttribution;
import com.qstory.backend.conversationrecord.entity.ConversationRecord;
import com.qstory.backend.conversationrecord.repository.ConversationRecordRepository;
import com.qstory.backend.provider.openrouter.RouteDecision;
import com.qstory.backend.provider.openrouter.RouteOption;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대화 원장(conversation_record)에 쓰는 유일한 경로. 읽기 메서드는 의도적으로 없다.
 *
 * <p>모든 메서드는 예외를 삼키고 로그만 남긴다 - 기록이 실패했다고 아이의 질문 응답이 실패해서는
 * 안 된다. 그래서 @Transactional 대신 TransactionTemplate을 try 안에서 쓴다: 어노테이션 방식은
 * insert가 커밋 시점(메서드 반환 후)에 실행되어 예외가 catch 밖으로 새어 나간다. REQUIRES_NEW라
 * 호출자의 트랜잭션(있다면)과도 분리된다.
 */
@Service
public class ConversationRecordService {

    private static final Logger log = LoggerFactory.getLogger(ConversationRecordService.class);

    private final ConversationRecordRepository repository;
    private final TransactionTemplate transaction;

    public ConversationRecordService(ConversationRecordRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 질문 지점 녹음의 STT 결과. 아직 답은 없다. */
    public void recordQuestionTranscript(
            String storyId, String sceneId, String anchorId, int questionRound,
            String transcript, String locale, String sourceMimeType, ConversationAttribution attribution) {
        save(base(ConversationRecordKind.QUESTION_TRANSCRIPT, storyId, sceneId, attribution.withInputMode(ConversationInputMode.VOICE))
                .anchorId(anchorId)
                .questionRound(questionRound)
                .locale(locale)
                .sourceMimeType(sourceMimeType)
                .childText(transcript)
                .build());
    }

    /** 질문 지점의 발화가 라우팅되어 캐릭터의 답이 정해진 결과. */
    public void recordQuestionRoute(
            String storyId, String sceneId, String anchorId, int questionRound,
            String transcript, String locale, String sourceMimeType,
            RouteDecision decision, ConversationAttribution attribution) {
        save(base(ConversationRecordKind.QUESTION_ROUTE, storyId, sceneId, attribution)
                .anchorId(anchorId)
                .questionRound(questionRound)
                .locale(locale)
                .sourceMimeType(sourceMimeType)
                .childText(transcript)
                .responseText(decision.responseText())
                .speakerId(decision.speakerId())
                .route(decision.route())
                .actionFamilyId(decision.actionFamilyId())
                .coverageStatus(decision.coverageStatus())
                .childRelevantMeaning(truncate(decision.childRelevantMeaning(), 500))
                .options(optionsOf(decision.options()))
                .modelId(decision.modelId())
                .promptVersion(decision.storyVersions() == null ? null : decision.storyVersions().promptVersion())
                .build());
    }

    /** 상시 대화 녹음의 STT 결과. */
    public void recordCompanionTranscript(
            String storyId, String sceneId, String transcript, String locale, String sourceMimeType,
            ConversationAttribution attribution) {
        save(base(ConversationRecordKind.COMPANION_TRANSCRIPT, storyId, sceneId, attribution.withInputMode(ConversationInputMode.VOICE))
                .locale(locale)
                .sourceMimeType(sourceMimeType)
                .childText(transcript)
                .build());
    }

    /** 상시 대화 한 턴 - 아이의 말과 캐릭터의 답. */
    public void recordCompanionTurn(
            String storyId, String sceneId, UUID conversationId, String transcript,
            OpenRouterClient.CompanionReply reply, String promptVersion, ConversationAttribution attribution) {
        ConversationAttribution withSession = attribution.sessionId() != null
                ? attribution
                : new ConversationAttribution(
                        conversationId, attribution.childId(), attribution.tutorStudentId(), attribution.lessonId(),
                        attribution.inputMode(), attribution.userId(), attribution.userRole());
        save(base(ConversationRecordKind.COMPANION_TURN, storyId, sceneId, withSession)
                .childText(transcript)
                .responseText(reply.responseText())
                .speakerId(reply.speakerId())
                .route(reply.interactionMode())
                .topicTag(reply.topicTag())
                .toneTag(reply.toneTag())
                .valueTag(reply.valueTag())
                .promptVersion(promptVersion)
                .build());
    }

    private static ConversationRecord.ConversationRecordBuilder base(
            ConversationRecordKind kind, String storyId, String sceneId, ConversationAttribution attribution) {
        return ConversationRecord.builder()
                .id(UUID.randomUUID())
                .recordedAt(Instant.now())
                .kind(kind)
                .sessionId(attribution.sessionId())
                .storyId(storyId)
                .sceneId(sceneId)
                .inputMode(attribution.inputMode() == null ? ConversationInputMode.TEXT : attribution.inputMode())
                .userId(attribution.userId())
                .userRole(attribution.userRole())
                .childId(attribution.childId())
                .tutorStudentId(attribution.tutorStudentId())
                .lessonId(attribution.lessonId());
    }

    private void save(ConversationRecord record) {
        try {
            transaction.executeWithoutResult(status -> repository.save(record));
        } catch (Exception error) {
            // 기록 실패는 아이 경험에 영향을 주지 않는다 - 그러나 조용히 묻히면 원장이 비는 것을
            // 아무도 모르므로 태그를 남긴다(Grafana: |= "conversation-record.failed").
            log.warn("conversation-record.failed kind={} storyId={} reason={}",
                    record.getKind(), record.getStoryId(), error.toString());
        }
    }

    private static List<Map<String, Object>> optionsOf(List<RouteOption> options) {
        if (options == null || options.isEmpty()) return null;
        return options.stream().map(option -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", option.id());
            map.put("label", option.label());
            map.put("meaning", option.meaning());
            map.put("actionFamilyId", option.actionFamilyId());
            map.put("branchLine", option.branchLine());
            return map;
        }).toList();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
