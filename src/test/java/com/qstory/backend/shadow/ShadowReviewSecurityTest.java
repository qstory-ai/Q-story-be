package com.qstory.backend.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.qstory.backend.betaevents.entity.StoryEvent;
import com.qstory.backend.betaevents.entity.StorySession;
import com.qstory.backend.common.enums.ReviewStatus;
import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.identity.security.CurrentUserResolver;
import com.qstory.backend.shadow.controller.ShadowReviewController;
import com.qstory.backend.shadow.entity.ShadowIntentCandidate;
import com.qstory.backend.shadow.repository.ShadowIntentRepository;
import com.qstory.backend.shadow.service.ShadowFamilyGenerationService;
import com.qstory.backend.shadow.service.ShadowIntentCollectionService;
import com.qstory.backend.shadow.service.ShadowReviewService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 보안 점검 H2 - 섀도 후보 검토는 STAFF만, 후보는 로그인한 서로 다른 계정 수로만 검토에 오른다. */
class ShadowReviewSecurityTest {

    private final ShadowReviewService reviewService = mock(ShadowReviewService.class);
    private final ShadowReviewController controller = new ShadowReviewController(
            reviewService, mock(ShadowFamilyGenerationService.class), new CurrentUserResolver());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(Role role) {
        CurrentUser user = new CurrentUser(UUID.randomUUID(), role, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @Test
    void reviewEndpointsNeedStaff() {
        UUID candidate = UUID.randomUUID();
        assertEquals(401, assertThrows(ApiException.class, controller::readyForReview).statusCode());
        assertEquals(401, assertThrows(ApiException.class, () -> controller.approve(candidate, null)).statusCode());
        assertEquals(401, assertThrows(ApiException.class, () -> controller.reject(candidate, null)).statusCode());

        login(Role.DIRECTOR);
        assertEquals(403, assertThrows(ApiException.class, controller::readyForReview).statusCode());
        assertEquals(403, assertThrows(ApiException.class, () -> controller.approve(candidate, null)).statusCode());
        assertEquals(403, assertThrows(ApiException.class, () -> controller.reject(candidate, null)).statusCode());
        verifyNoInteractions(reviewService);

        login(Role.STAFF);
        controller.readyForReview();
        controller.approve(candidate, null);
        controller.reject(candidate, null);
        verify(reviewService).readyForReview();
        verify(reviewService).approve(candidate, null);
        verify(reviewService).reject(candidate, null);
    }

    private ShadowIntentCandidate collect(long distinctSessions, long distinctUsers) {
        ShadowIntentRepository repository = mock(ShadowIntentRepository.class);
        ShadowIntentCandidate candidate = ShadowIntentCandidate.builder().id(UUID.randomUUID()).build();
        when(repository.findCandidate(anyString(), anyString(), anyString())).thenReturn(Optional.of(candidate));
        when(repository.findCandidateById(candidate.getId())).thenReturn(Optional.of(candidate));
        when(repository.countObservations(candidate.getId())).thenReturn(distinctSessions);
        when(repository.countDistinctSessions(candidate.getId())).thenReturn(distinctSessions);
        when(repository.countDistinctUsers(candidate.getId())).thenReturn(distinctUsers);
        when(repository.saveCandidate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StoryEvent event = StoryEvent.builder().id(UUID.randomUUID()).metadata(Map.of(
                "result", "route_accepted", "coverage_status", "uncovered", "anchor_id", "HG-Q-A",
                "question_intent", "마녀는 왜 과자집에 살아?")).build();
        new ShadowIntentCollectionService(repository).collectFromQuestionResultEvent(event, new StorySession());
        return candidate;
    }

    @Test
    void anonymousSessionsAloneDoNotPromote() {
        assertEquals(ReviewStatus.COLLECTING, collect(10, 0).getReviewStatus());
        assertEquals(ReviewStatus.COLLECTING, collect(10, 2).getReviewStatus());
    }

    @Test
    void threeDistinctAccountsPromote() {
        assertEquals(ReviewStatus.READY_FOR_REVIEW, collect(3, 3).getReviewStatus());
    }
}
