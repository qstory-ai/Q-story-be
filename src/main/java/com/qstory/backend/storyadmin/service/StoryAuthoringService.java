package com.qstory.backend.storyadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qstory.backend.common.enums.RevisionOperation;
import com.qstory.backend.common.enums.RevisionTarget;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.story.entity.StoryRevision;
import com.qstory.backend.story.entity.StoryScene;
import com.qstory.backend.story.entity.StorySegment;
import com.qstory.backend.story.repository.StorySceneRepository;
import com.qstory.backend.story.repository.StorySegmentRepository;
import com.qstory.backend.storyadmin.dto.RevertRequest;
import com.qstory.backend.storyadmin.dto.SceneEditRequest;
import com.qstory.backend.storyadmin.dto.SceneView;
import com.qstory.backend.storyadmin.dto.SegmentEditRequest;
import com.qstory.backend.storyadmin.dto.SegmentView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Edits one piece of a story at a time.
 *
 * <p>Until now the only way to change anything was POST /v1/admin/stories/import, which replaces
 * the entire story - fine while the content files were the source and the DB was a copy, useless
 * once someone wants to fix a single line of narration. Every write here goes through
 * {@link StoryRevisionService} so the change is attributable and reversible.
 */
@Service
public class StoryAuthoringService {

    private final StorySceneRepository sceneRepository;
    private final StorySegmentRepository segmentRepository;
    private final StoryRevisionService revisionService;
    private final ObjectMapper objectMapper;

    public StoryAuthoringService(
            StorySceneRepository sceneRepository,
            StorySegmentRepository segmentRepository,
            StoryRevisionService revisionService,
            ObjectMapper objectMapper) {
        this.sceneRepository = sceneRepository;
        this.segmentRepository = segmentRepository;
        this.revisionService = revisionService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SceneView> scenes(String storyId) {
        return sceneRepository.findByStory_IdOrderBySequenceAsc(storyId).stream()
                .map(SceneView::of)
                .toList();
    }

    @Transactional
    public SceneView editScene(String storyId, String sceneId, SceneEditRequest request, UUID authorId) {
        requireCurrent(storyId, request.baseRevision());
        StoryScene scene = requireScene(storyId, sceneId);

        SceneView before = SceneView.of(scene);
        if (request.title() != null) scene.setTitle(request.title());
        if (request.sequence() != null) scene.setSequence(request.sequence());
        SceneView after = SceneView.of(sceneRepository.save(scene));

        revisionService.record(
                storyId, RevisionTarget.SCENE, sceneId, RevisionOperation.UPDATE,
                before, after, authorId, request.summary());
        return after;
    }

    @Transactional(readOnly = true)
    public List<SegmentView> segments(String storyId, String sceneId) {
        requireScene(storyId, sceneId);
        return segmentRepository.findByScene_IdOrderByDisplayOrderAsc(sceneId).stream()
                .map(SegmentView::of)
                .toList();
    }

    @Transactional
    public SegmentView editSegment(
            String storyId, UUID segmentId, SegmentEditRequest request, UUID authorId) {
        requireCurrent(storyId, request.baseRevision());
        if (request.payload() == null || request.payload().isEmpty()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "payload가 필요해요.", 400);
        }
        StorySegment segment = segmentRepository.findById(segmentId)
                .filter(candidate -> candidate.getScene().getStory().getId().equals(storyId))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "그 문장을 찾지 못했어요.", 404));

        SegmentView before = SegmentView.of(segment);
        // narrationText is deliberately untouched: it records what the audio says, which an edit
        // to the script does not change. SegmentView compares the two.
        segment.setPayload(request.payload());
        SegmentView after = SegmentView.of(segmentRepository.save(segment));

        revisionService.record(
                storyId, RevisionTarget.SEGMENT, segmentId.toString(), RevisionOperation.UPDATE,
                before, after, authorId, request.summary());
        return after;
    }

    /**
     * Puts back whatever the named revision changed, as it stood before that edit.
     *
     * <p>Recorded as a new revision rather than by deleting the one being undone: an undo is
     * something that happened, and a history you can rewrite is not an audit trail.
     */
    @Transactional
    public Map<String, Object> revert(String storyId, RevertRequest request, UUID authorId) {
        requireCurrent(storyId, request.baseRevision());
        if (request.revision() == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "revision이 필요해요.", 400);
        }
        StoryRevision target = revisionService.history(storyId).stream()
                .filter(row -> row.getRevision().equals(request.revision()))
                .findFirst()
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "그 기록을 찾지 못했어요.", 404));
        if (target.getBeforeState() == null) {
            throw ApiException.contractError(
                    ErrorCode.VALIDATION_FAILED,
                    "이 기록은 되돌릴 이전 상태가 없어요.",
                    400);
        }

        Map<String, Object> restored = switch (target.getTargetType()) {
            case SCENE -> restoreScene(storyId, target.getTargetId(), target.getBeforeState());
            case SEGMENT -> restoreSegment(storyId, target.getTargetId(), target.getBeforeState());
            default -> throw ApiException.contractError(
                    ErrorCode.VALIDATION_FAILED,
                    "아직 되돌릴 수 없는 종류예요.",
                    400);
        };

        revisionService.record(
                storyId, target.getTargetType(), target.getTargetId(), RevisionOperation.UPDATE,
                target.getAfterState(), restored, authorId,
                request.summary() == null ? "revert of revision " + request.revision() : request.summary());
        return restored;
    }

    private Map<String, Object> restoreScene(String storyId, String sceneId, Map<String, Object> before) {
        StoryScene scene = requireScene(storyId, sceneId);
        scene.setTitle((String) before.get("title"));
        scene.setSequence(((Number) before.get("sequence")).intValue());
        return asMap(SceneView.of(sceneRepository.save(scene)));
    }

    private Map<String, Object> restoreSegment(String storyId, String segmentId, Map<String, Object> before) {
        StorySegment segment = segmentRepository.findById(UUID.fromString(segmentId))
                .filter(candidate -> candidate.getScene().getStory().getId().equals(storyId))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "그 문장을 찾지 못했어요.", 404));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) before.get("payload");
        segment.setPayload(payload);
        return asMap(SegmentView.of(segmentRepository.save(segment)));
    }

    private StoryScene requireScene(String storyId, String sceneId) {
        return sceneRepository.findById(sceneId)
                .filter(candidate -> candidate.getStory().getId().equals(storyId))
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "그 장면을 찾지 못했어요.", 404));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }

    /**
     * Refuses a write aimed at a story that has moved on since the editor loaded it. Compared here
     * rather than per row because a story is edited as a whole - a scene retitled against a stale
     * view of its neighbours is just as wrong as one written over.
     */
    private void requireCurrent(String storyId, Integer baseRevision) {
        if (baseRevision == null) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "baseRevision이 필요해요.", 400);
        }
        int current = revisionService.currentRevision(storyId);
        if (baseRevision != current) {
            throw ApiException.contractError(
                    ErrorCode.STALE_REVISION,
                    "다른 사람이 먼저 수정했어요. 최신 내용을 불러온 뒤 다시 시도해 주세요.",
                    409);
        }
    }
}
