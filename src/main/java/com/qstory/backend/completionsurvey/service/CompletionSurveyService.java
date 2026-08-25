package com.qstory.backend.completionsurvey.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.completionsurvey.dto.CompletionSurveySubmission;
import com.qstory.backend.completionsurvey.entity.CompletionSurvey;
import com.qstory.backend.completionsurvey.repository.CompletionSurveyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 완주 후 부모 리포트 화면에서 남기는 "1분 체험 후기" - 기존 외부 Google Form과 동일한 문항을
 * 인앱 모달(CompletionSurveyModal.tsx)로 받는다. LaunchNotificationService와 같은 이유로
 * (익명 제출, 인증 세션 없음) 클라이언트 검증을 다시 믿지 않고 여기서 재확인한다.
 *
 * <p>닫힌 선택지(단일 선택이고 "기타" 자유 입력이 없는 문항)만 허용 목록으로 엄격히 검증한다 -
 * "기타"가 있는 문항(bestAspect)과 체크박스 문항(childReactions/disruptions)은 프론트 UI 문구가
 * 나중에 바뀌어도 배포 없이 값을 받을 수 있도록 비어있지 않은지/길이만 확인한다.
 */
@Service
public class CompletionSurveyService {

    private static final Set<String> CHILD_AGE_BANDS =
            Set.of("5세 이하", "6세", "7세", "8세", "9세", "10세 이상");
    private static final Set<String> INPUT_UNDERSTANDING_OPTIONS = Set.of(
            "아이가 먼저 “내가 말해서 바뀌었어”라고 표현했어요",
            "말로 표현하지는 않았지만 달라진 장면에 반응했어요",
            "부모가 설명해 주자 이해했어요",
            "알아차리지 못한 것 같아요",
            "잘 모르겠어요");
    private static final Set<String> HELP_NEEDED_OPTIONS = Set.of(
            "거의 혼자 듣고 답했어요",
            "가끔 질문을 다시 설명해 줬어요",
            "여러 번 부모가 답변을 도와줬어요",
            "아이가 직접 답하기 어려워했어요");
    private static final Set<String> RETRY_INTEREST_OPTIONS = Set.of(
            "나오면 꼭 다시 체험하고 싶어요",
            "아마 다시 체험할 것 같아요",
            "아직 잘 모르겠어요",
            "아마 다시 체험하지 않을 것 같아요",
            "다시 체험할 생각이 없어요");
    private static final Set<String> REVIEW_USAGE_CONSENT_OPTIONS = Set.of(
            "아이 나이대와 함께 익명으로 공개해도 괜찮아요",
            "Q-Story 내부 개선에만 사용해 주세요",
            "한 줄 후기를 작성하지 않았어요");
    private static final Set<String> WANTS_NEXT_STORIES_OPTIONS =
            Set.of("네, 안내받을 연락처를 남길게요", "이번에는 괜찮아요");
    private static final Set<String> CONTACT_CONSENT_OPTIONS =
            Set.of("동의합니다", "연락처를 남기지 않았습니다");

    private static final int MIN_SCALE = 1;
    private static final int MAX_SCALE = 5;
    private static final int MAX_CHECKBOX_ITEMS = 20;
    private static final int MAX_CHECKBOX_ITEM_LENGTH = 200;

    private final CompletionSurveyRepository repository;

    public CompletionSurveyService(CompletionSurveyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void submit(CompletionSurveySubmission submission) {
        String storyId = requireText(submission.storyId(), 64, "storyId가 없어요.");
        String childAgeBand =
                requireOneOf(submission.childAgeBand(), CHILD_AGE_BANDS, "아이 나이를 선택해 주세요.");
        int childEngagement = requireScale(submission.childEngagement(), "아이의 몰입도를 선택해 주세요.");
        String inputUnderstanding = requireOneOf(
                submission.inputUnderstanding(),
                INPUT_UNDERSTANDING_OPTIONS,
                "아이의 반응을 선택해 주세요.");
        String helpNeeded =
                requireOneOf(submission.helpNeeded(), HELP_NEEDED_OPTIONS, "도움 정도를 선택해 주세요.");
        List<String> childReactions =
                requireChecklist(submission.childReactions(), "아이가 보인 반응을 선택해 주세요.");
        List<String> disruptions =
                requireChecklist(submission.disruptions(), "불편했던 점을 선택해 주세요.");
        int reportHelpfulness =
                requireScale(submission.reportHelpfulness(), "부모 리포트 도움 정도를 선택해 주세요.");
        String bestAspect = requireText(submission.bestAspect(), 200, "가장 좋았던 점을 선택해 주세요.");
        String topPriority = optionalText(submission.topPriority(), 500);
        String retryInterest = requireOneOf(
                submission.retryInterest(), RETRY_INTEREST_OPTIONS, "다시 체험할 의향을 선택해 주세요.");
        String oneLineReview = optionalText(submission.oneLineReview(), 500);
        String reviewUsageConsent = requireOneOf(
                submission.reviewUsageConsent(),
                REVIEW_USAGE_CONSENT_OPTIONS,
                "후기 사용 동의 여부를 선택해 주세요.");
        String wantsNextStories = requireOneOf(
                submission.wantsNextStories(),
                WANTS_NEXT_STORIES_OPTIONS,
                "다음 이야기 체험 의향을 선택해 주세요.");
        String contact = optionalText(submission.contact(), 254);
        String contactConsent = requireOneOf(
                submission.contactConsent(), CONTACT_CONSENT_OPTIONS, "연락처 수집 동의 여부를 선택해 주세요.");

        repository.save(CompletionSurvey.builder()
                .storyId(storyId)
                .childAgeBand(childAgeBand)
                .childEngagement(childEngagement)
                .inputUnderstanding(inputUnderstanding)
                .helpNeeded(helpNeeded)
                .childReactions(childReactions)
                .disruptions(disruptions)
                .reportHelpfulness(reportHelpfulness)
                .bestAspect(bestAspect)
                .topPriority(topPriority)
                .retryInterest(retryInterest)
                .oneLineReview(oneLineReview)
                .reviewUsageConsent(reviewUsageConsent)
                .wantsNextStories(wantsNextStories)
                .contact(contact)
                .contactConsent(contactConsent)
                .createdAt(Instant.now())
                .build());
    }

    private String requireOneOf(String value, Set<String> allowed, String safeDetail) {
        String trimmed = value == null ? "" : value.trim();
        if (!allowed.contains(trimmed)) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, safeDetail);
        }
        return trimmed;
    }

    private int requireScale(Integer value, String safeDetail) {
        if (value == null || value < MIN_SCALE || value > MAX_SCALE) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, safeDetail);
        }
        return value;
    }

    private List<String> requireChecklist(List<String> values, String safeDetail) {
        if (values == null || values.isEmpty() || values.size() > MAX_CHECKBOX_ITEMS) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, safeDetail);
        }
        for (String value : values) {
            if (value == null || value.trim().isEmpty() || value.length() > MAX_CHECKBOX_ITEM_LENGTH) {
                throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, safeDetail);
            }
        }
        return values;
    }

    private String requireText(String value, int maxLength, String safeDetail) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, safeDetail);
        }
        return trimmed;
    }

    /** topPriority/oneLineReview/contact는 모두 선택 입력이다 - 비어 있으면 null로 저장한다. */
    private String optionalText(String value, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, "입력한 내용이 너무 길어요.");
        }
        return trimmed;
    }
}
