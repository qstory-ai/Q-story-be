package com.qstory.backend.livebranch.service;

import com.qstory.backend.common.enums.FamilyOrigin;
import com.qstory.backend.common.enums.LiveBranchJobStatus;
import com.qstory.backend.familydraft.util.FamilyDraftHarness;
import com.qstory.backend.livebranch.entity.LiveBranchJob;
import com.qstory.backend.livebranch.repository.LiveBranchJobRepository;
import com.qstory.backend.story.StoryContext;
import com.qstory.backend.story.repository.StoryActionFamilyRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * route_classifier가 NEW_CHOICES를 고르면(QuestionRoutingService 참고) 새 선택지 생성을 큐에
 * 넣는다. 클래스명은 Phase 1("새 분기 1개") 때 그대로지만 역할은 Phase 2부터 "최대 3개의 새 선택지"로
 * 넓어졌다 - 실제 생성(최대 3개 병렬 LLM/이미지 호출 + 커밋)은 별도 빈인 LiveBranchExecutionWorker의
 * @Async 메서드가 수행한다 - 같은 빈 안에서 @Async 메서드를 self-invocation 하면 Spring의 프록시가
 * 우회되어 비동기로 실행되지 않으므로, 반드시 다른 빈을 통해 호출해야 한다.
 */
@Service
public class LiveBranchGenerationService {

    /**
     * 앵커당 라이브 생성 family 상한. 없으면 매 라우팅 호출마다 프롬프트에 실리는
     * allowedActionFamilies/approvedChoiceCopyBank가 무한정 커지고, 스토리 전체 번들도 그만큼
     * 비대해진다 - 상한 도달 시 조용히 생성을 건너뛰고 평소 ANSWER_RESUME 응답을 그대로 둔다.
     */
    private static final int MAX_LIVE_FAMILIES_PER_ANCHOR = 5;

    /** DB에 남기기 전 redact()로 지운 아이 발화의 최대 길이 - childTranscriptRedacted 컬럼 크기에 맞춘다. */
    private static final int REDACTED_TRANSCRIPT_MAX_LENGTH = 400;

    private final LiveBranchJobRepository jobRepository;
    private final StoryActionFamilyRepository familyRepository;
    private final LiveBranchExecutionWorker executionWorker;
    private final FamilyDraftHarness harness;

    public LiveBranchGenerationService(
            LiveBranchJobRepository jobRepository, StoryActionFamilyRepository familyRepository,
            LiveBranchExecutionWorker executionWorker, FamilyDraftHarness harness) {
        this.jobRepository = jobRepository;
        this.familyRepository = familyRepository;
        this.executionWorker = executionWorker;
        this.harness = harness;
    }

    /** 상한에 걸려 생성을 건너뛰면 null을 반환한다 - 호출자는 이 경우 decision을 그대로 둔다. */
    public String enqueue(StoryContext storyContext, String transcript, int questionRound) {
        long liveFamilyCount = familyRepository.countByAnchor_IdAndOrigin(
                storyContext.anchorId(), FamilyOrigin.LIVE_GENERATED);
        if (liveFamilyCount >= MAX_LIVE_FAMILIES_PER_ANCHOR) {
            return null;
        }
        Instant now = Instant.now();
        LiveBranchJob job = jobRepository.save(LiveBranchJob.builder()
                .storyId(storyContext.storyId())
                .anchorId(storyContext.anchorId())
                .childTranscriptRedacted(harness.redact(transcript, REDACTED_TRANSCRIPT_MAX_LENGTH))
                .questionRound(questionRound)
                .status(LiveBranchJobStatus.QUEUED)
                .createdAt(now)
                .updatedAt(now)
                .build());
        executionWorker.run(job.getId());
        return job.getId().toString();
    }
}
