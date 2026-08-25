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
 * 스토리를 한 번에 한 조각씩 수정한다.
 *
 * <p>지금까지 무언가를 바꾸는 유일한 방법은 스토리 전체를 교체하는 POST /v1/admin/stories/import뿐
 * 이었다 - 콘텐츠 파일이 원본이고 DB는 그 사본에 불과했던 동안에는 괜찮았지만, 내레이션 한 줄만
 * 고치고 싶어지는 순간부터는 쓸모가 없어진다. 여기서 이루어지는 모든 쓰기는
 * {@link StoryRevisionService}를 거치므로 변경 사항의 책임 소재를 알 수 있고 되돌릴 수도 있다.
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
        // narrationText는 의도적으로 건드리지 않는다: 이 필드는 오디오가 실제로 말하는 내용을
        // 기록하는 것이며, 대본을 수정한다고 해서 바뀌지 않는다. SegmentView가 이 둘을 비교한다.
        segment.setPayload(request.payload());
        SegmentView after = SegmentView.of(segmentRepository.save(segment));

        revisionService.record(
                storyId, RevisionTarget.SEGMENT, segmentId.toString(), RevisionOperation.UPDATE,
                before, after, authorId, request.summary());
        return after;
    }

    /**
     * 지정한 리비전이 변경한 내용을, 그 수정이 있기 전 상태로 되돌린다.
     *
     * <p>되돌리려는 리비전을 삭제하는 대신 새 리비전으로 기록한다: undo(되돌리기) 역시 실제로
     * 일어난 일이며, 다시 쓸 수 있는 이력은 감사 추적(audit trail)이 아니다.
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
     * 편집자가 스토리를 불러온 이후 그 스토리가 이미 앞서 나간 상태라면 쓰기 요청을 거부한다. 행(row)
     * 단위가 아니라 여기서 비교하는 이유는 스토리가 하나의 전체로서 편집되기 때문이다 - 이웃 요소들의
     * 오래된 상태를 기준으로 제목이 바뀐 scene은, 그냥 덮어써진 것만큼이나 잘못된 것이다.
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
