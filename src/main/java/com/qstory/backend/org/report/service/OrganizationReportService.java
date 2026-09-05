package com.qstory.backend.org.report.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.ClassGroup;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.report.dto.OrganizationReportResponse;
import com.qstory.backend.storyreport.entity.StoryCompletion;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationReportService {

    private static final int TOP_STORY_LIMIT = 5;

    private final OrganizationRepository organizationRepository;
    private final ClassGroupRepository classGroupRepository;
    private final AppUserRepository userRepository;
    private final StoryCompletionRepository completionRepository;

    public OrganizationReportService(
            OrganizationRepository organizationRepository,
            ClassGroupRepository classGroupRepository,
            AppUserRepository userRepository,
            StoryCompletionRepository completionRepository) {
        this.organizationRepository = organizationRepository;
        this.classGroupRepository = classGroupRepository;
        this.userRepository = userRepository;
        this.completionRepository = completionRepository;
    }

    @Transactional(readOnly = true)
    public OrganizationReportResponse read(CurrentUser caller, UUID organizationId) {
        requireOwnedByCaller(caller, organizationId);
        List<ClassGroup> classes = classGroupRepository.findByOrganization_IdOrderByCreatedAtAsc(organizationId);
        List<StoryCompletion> completions = completionRepository
                .findByOrganization_IdOrderByCompletedAtDesc(organizationId);

        Map<UUID, Aggregate> byClass = new HashMap<>();
        Map<String, Long> byStory = new HashMap<>();
        long questionCount = 0;
        for (StoryCompletion completion : completions) {
            long questionCountForCompletion = completion.getOutcomes() == null ? 0 : completion.getOutcomes().size();
            questionCount += questionCountForCompletion;
            byStory.merge(completion.getStoryId(), 1L, Long::sum);
            ClassGroup classGroup = completion.getClassGroup();
            if (classGroup != null) {
                byClass.computeIfAbsent(classGroup.getId(), ignored -> new Aggregate())
                        .add(questionCountForCompletion, completion.getCompletedAt());
            }
        }

        List<OrganizationReportResponse.ClassSummary> classSummaries = classes.stream()
                .map(classGroup -> {
                    Aggregate aggregate = byClass.getOrDefault(classGroup.getId(), new Aggregate());
                    return new OrganizationReportResponse.ClassSummary(
                            classGroup.getId(),
                            classGroup.getName(),
                            userRepository.countByClassGroup_IdAndRoleAndDeletedAtIsNull(classGroup.getId(), Role.PARENT),
                            aggregate.completionCount,
                            aggregate.questionCount,
                            aggregate.lastActivityAt);
                })
                .toList();
        List<OrganizationReportResponse.StorySummary> topStories = byStory.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_STORY_LIMIT)
                .map(entry -> new OrganizationReportResponse.StorySummary(entry.getKey(), entry.getValue()))
                .toList();

        return new OrganizationReportResponse(Instant.now(), completions.size(), questionCount, classSummaries, topStories);
    }

    private void requireOwnedByCaller(CurrentUser caller, UUID organizationId) {
        if (caller.orgId() == null || !caller.orgId().equals(organizationId)) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 기관의 리포트를 볼 권한이 없어요.", 403);
        }
        if (!organizationRepository.existsById(organizationId)) {
            throw ApiException.contractError(ErrorCode.NOT_FOUND, "기관을 찾을 수 없어요.", 404);
        }
    }

    private static final class Aggregate {
        private long completionCount;
        private long questionCount;
        private Instant lastActivityAt;

        private void add(long questions, Instant completedAt) {
            completionCount++;
            questionCount += questions;
            if (lastActivityAt == null || completedAt.isAfter(lastActivityAt)) {
                lastActivityAt = completedAt;
            }
        }
    }
}
