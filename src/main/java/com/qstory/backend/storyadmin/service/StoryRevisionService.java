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
 * Appends the audit trail every authoring write goes through.
 *
 * <p>Story content is leaving the git-tracked content files for the database, so the reviewability
 * git provided has to be rebuilt here: who changed what, what it held before, and how to get back.
 * A write that skips this service is a write nobody can explain later.
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
     * The caller's view of how current the story was when they started editing. An editor sends it
     * back with a write; a mismatch means someone else has since written, and the write is refused
     * rather than silently overwriting them.
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
     * Records one edit. Runs in the caller's transaction on purpose: the revision and the change it
     * describes commit together, so history can never claim an edit that rolled back.
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

    /** Entities carry lazy associations that must not be walked into the snapshot, hence the DTOs. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(Object value) {
        return value == null ? null : objectMapper.convertValue(value, Map.class);
    }
}
