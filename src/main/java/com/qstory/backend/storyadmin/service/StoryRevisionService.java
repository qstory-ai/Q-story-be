package com.qstory.backend.storyadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.enums.RevisionOperation;
import com.qstory.backend.common.enums.RevisionTarget;
import com.qstory.backend.story.entity.StoryRevision;
import com.qstory.backend.story.repository.StoryRevisionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모든 저작(authoring) 쓰기 작업이 거쳐가는 감사 추적(audit trail)을 기록한다.
 *
 * <p>스토리 콘텐츠가 git으로 추적되던 콘텐츠 파일에서 데이터베이스로 옮겨가고 있으므로, git이
 * 제공하던 검토 가능성(reviewability)을 여기서 다시 구축해야 한다: 누가 무엇을 바꿨는지, 이전에는
 * 무엇이 들어 있었는지, 어떻게 되돌릴 수 있는지. 이 서비스를 거치지 않은 쓰기는 나중에 아무도
 * 설명할 수 없는 쓰기가 된다.
 */
@Service
public class StoryRevisionService {

    private final StoryRevisionRepository repository;
    private final ObjectMapper objectMapper;

    public StoryRevisionService(StoryRevisionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 호출자가 편집을 시작했을 때 스토리가 얼마나 최신 상태였는지를 나타내는 값이다. 편집자는 쓰기
     * 요청을 보낼 때 이 값을 함께 돌려보내며, 값이 일치하지 않으면 그 사이 다른 누군가가 이미 썼다는
     * 뜻이므로, 조용히 덮어쓰는 대신 쓰기를 거부한다.
     */
    @Transactional(readOnly = true)
    public int currentRevision(String storyId) {
        return repository.findFirstByStoryIdOrderByRevisionDesc(storyId)
                .map(StoryRevision::getRevision)
                .orElse(0);
    }

    @Transactional(readOnly = true)
    public List<StoryRevision> history(String storyId) {
        return repository.findByStoryIdOrderByRevisionDesc(storyId);
    }

    @Transactional(readOnly = true)
    public List<StoryRevision> historyFor(String storyId, RevisionTarget targetType, String targetId) {
        return repository.findByStoryIdAndTargetTypeAndTargetIdOrderByRevisionDesc(
                storyId, targetType, targetId);
    }

    /**
     * 수정 사항 하나를 기록한다. 의도적으로 호출자의 트랜잭션 안에서 실행된다: 리비전과 그것이
     * 설명하는 변경 사항이 함께 커밋되므로, 롤백된 수정이 이력에 남아 있는 일은 절대 없다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StoryRevision record(
            String storyId,
            RevisionTarget targetType,
            String targetId,
            RevisionOperation operation,
            Object before,
            Object after,
            UUID authorId,
            String summary) {
        return repository.save(StoryRevision.builder()
                .storyId(storyId)
                .revision(currentRevision(storyId) + 1)
                .targetType(targetType)
                .targetId(targetId)
                .operation(operation)
                .beforeState(snapshot(before))
                .afterState(snapshot(after))
                .authorId(authorId)
                .summary(summary)
                .createdAt(Instant.now())
                .build());
    }

    /** 엔티티는 스냅샷에 그대로 담기면 안 되는 지연 로딩(lazy) 연관관계를 갖고 있어서, DTO를 쓰는 이유가 여기에 있다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(Object value) {
        return value == null ? null : objectMapper.convertValue(value, Map.class);
    }
}
