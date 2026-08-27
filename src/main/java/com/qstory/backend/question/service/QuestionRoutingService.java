package com.qstory.backend.question.service;

import com.qstory.backend.common.enums.CoverageStatus;
import com.qstory.backend.common.error.ProviderErrorCode;
import com.qstory.backend.common.error.ProviderException;
import com.qstory.backend.common.util.RequestDeadline;
import com.qstory.backend.livebranch.service.LiveBranchGenerationService;
import com.qstory.backend.provider.openrouter.ContentGeneration;
import com.qstory.backend.provider.openrouter.RouteClassification;
import com.qstory.backend.provider.openrouter.RouteDecision;
import com.qstory.backend.provider.openrouter.RouteOption;
import com.qstory.backend.provider.openrouter.SafetyVerdict;
import com.qstory.backend.provider.openrouter.util.OpenRouterClient;
import com.qstory.backend.provider.openrouter.util.RouteResultValidator;
import com.qstory.backend.story.StoryContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Phase 2의 3단계 라우팅 파이프라인 오케스트레이터. 예전 {@code OpenRouterClient.generatePlan()} 단일
 * 호출을 대체한다: 1단계 안전게이트(evaluateSafety) -&gt; (REDIRECT면 여기서 끝, PASS면) 2단계
 * 분류기(classifyRoute) -&gt; (route==NEW_CHOICES면 stage3 생략하고 실시간 새 선택지 생성만 트리거,
 * 그 외에는) 3단계 생성기(generateContent) -&gt; 최종 RouteDecision 조립 -&gt;
 * RouteResultValidator.guaranteeBetaAgencyChoice/sanitizeGeneratedOptionCopy.
 *
 * <p>Phase 2 §2(안전게이트 3회 연속 실패 -&gt; 재질문 중단)는 클라이언트 상태(무상태, questionRound와
 * 같은 패턴)로만 구현된다 - 이 서비스는 매 호출을 독립적으로 처리하고 카운터를 들고 있지 않는다.
 */
@Service
public class QuestionRoutingService {

    /** 새 분기 생성을 트리거했을 때, 백그라운드 생성이 끝날 때까지 아이에게 들려줄 고정 안내 문구. */
    private static final String LIVE_BRANCH_ACKNOWLEDGEMENT_TEXT = "그것도 한번 알아볼게, 잠깐만 기다려줘!";

    /**
     * 앵커당 라이브 생성 family 상한(LiveBranchGenerationService.enqueue 참고)에 걸려 새 선택지
     * 생성을 건너뛸 때, 아이에게 대신 들려줄 안전한 ANSWER_RESUME 상당 응답 - Phase 1의 캡 처리와
     * 같은 철학(조용히 안전한 기본 응답으로 폴백).
     */
    private static final String NEW_CHOICES_CAP_FALLBACK_TEXT = "그건 지금은 어려울 것 같아, 원래 하려던 이야기를 먼저 계속해보자.";

    private final OpenRouterClient openRouterClient;
    private final RouteResultValidator routeResultValidator;
    private final LiveBranchGenerationService liveBranchGenerationService;

    public QuestionRoutingService(
            OpenRouterClient openRouterClient, RouteResultValidator routeResultValidator,
            LiveBranchGenerationService liveBranchGenerationService) {
        this.openRouterClient = openRouterClient;
        this.routeResultValidator = routeResultValidator;
        this.liveBranchGenerationService = liveBranchGenerationService;
    }

    public RouteDecision route(
            StoryContext storyContext, String transcript, int questionRound, boolean guaranteeAgencyChoice,
            RequestDeadline deadline) {
        SafetyVerdict verdict = openRouterClient.evaluateSafety(
                new OpenRouterClient.SafetyGateRequest(transcript, storyContext), deadline);

        RouteDecision decision;
        if (verdict.isRedirect()) {
            decision = buildRedirectDecision(verdict, storyContext);
        } else {
            RouteClassification classification = openRouterClient.classifyRoute(
                    new OpenRouterClient.ClassifyRequest(transcript, storyContext, questionRound), deadline);
            // clarificationAlreadyUsed였는데도 분류기가 다시 CLARIFY_ONCE를 고르면, 같은 질문을 또
            // 확인하지 않고 이야기로 돌아간다 - 예전 generatePlan()이 하던 것과 동일한 규칙.
            if (questionRound > 1 && "CLARIFY_ONCE".equals(classification.route())) {
                throw new ProviderException(
                        ProviderErrorCode.OPENROUTER_SECOND_CLARIFICATION, "같은 질문을 다시 확인하지 않고 이야기로 돌아갈게요.");
            }
            decision = "NEW_CHOICES".equals(classification.route())
                    ? buildNewChoicesDecision(classification, storyContext, transcript, questionRound)
                    : buildGeneratedContentDecision(classification, storyContext, deadline);
        }

        RouteDecision agencyAware = routeResultValidator.guaranteeBetaAgencyChoice(
                decision, storyContext, transcript, guaranteeAgencyChoice, questionRound);
        return routeResultValidator.sanitizeGeneratedOptionCopy(agencyAware, storyContext, transcript, questionRound);
    }

    private RouteDecision buildRedirectDecision(SafetyVerdict verdict, StoryContext storyContext) {
        return new RouteDecision(
                "GENTLE_REDIRECT",
                verdict.redirectReason(),
                CoverageStatus.EXACT.wireValue(),
                verdict.redirectReason(),
                verdict.responseText(),
                storyContext.primarySpeakerId(),
                null, null, null, List.of(),
                verdict.modelId(), storyContext.versions(), null);
    }

    private RouteDecision buildGeneratedContentDecision(
            RouteClassification classification, StoryContext storyContext, RequestDeadline deadline) {
        int optionSlots = "THREE_PATHS".equals(classification.route()) ? 3 : 0;
        ContentGeneration content = openRouterClient.generateContent(
                new OpenRouterClient.ContentRequest(
                        classification.route(), classification.childRelevantMeaning(), classification.coverageStatus(),
                        classification.speakerId(), storyContext, optionSlots),
                deadline);
        return new RouteDecision(
                classification.route(), classification.childRelevantMeaning(), classification.coverageStatus(),
                classification.coverageReason(), content.responseText(), classification.speakerId(),
                classification.actionFamilyId(), classification.rejoinAnchorId(), classification.fallbackFamilyId(),
                content.options(), classification.modelId(), storyContext.versions(), null);
    }

    /**
     * Phase 1의 "uncovered + 아무 콘텐츠도 안 붙음" 트리거를 대체한다 - 이제는 라벨이 아니라 분류기
     * 자신이 명시적으로 고른 NEW_CHOICES route로 판정한다. stage3는 실행하지 않는다: 실제 콘텐츠는
     * LiveBranchExecutionWorker가 비동기로 최대 3개까지 만들고, 프런트는 liveBranchJobId를 폴링해
     * READY가 되면 그 결과로 THREE_PATHS를 구성한다(계획 문서 Phase 2 §3).
     */
    private RouteDecision buildNewChoicesDecision(
            RouteClassification classification, StoryContext storyContext, String transcript, int questionRound) {
        String jobId = liveBranchGenerationService.enqueue(storyContext, transcript, questionRound);
        if (jobId == null) {
            // 앵커당 상한에 걸려 조용히 건너뛴 경우 - 안전한 ANSWER_RESUME 상당 응답으로 폴백한다.
            return new RouteDecision(
                    "ANSWER_RESUME", classification.childRelevantMeaning(), CoverageStatus.UNCOVERED.wireValue(),
                    classification.coverageReason(), NEW_CHOICES_CAP_FALLBACK_TEXT, classification.speakerId(),
                    null, null, null, List.of(), classification.modelId(), storyContext.versions(), null);
        }
        RouteDecision withoutJob = new RouteDecision(
                "NEW_CHOICES", classification.childRelevantMeaning(), classification.coverageStatus(),
                classification.coverageReason(), LIVE_BRANCH_ACKNOWLEDGEMENT_TEXT, classification.speakerId(),
                null, null, null, List.<RouteOption>of(), classification.modelId(), storyContext.versions(), null);
        return withoutJob.withLiveBranchJob(jobId);
    }
}
