package com.qstory.backend.org.usage.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.Role;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.org.entity.Organization;
import com.qstory.backend.org.repository.ClassGroupRepository;
import com.qstory.backend.org.repository.OrganizationRepository;
import com.qstory.backend.org.tutor.repository.OrganizationTutorRepository;
import com.qstory.backend.org.usage.dto.OrganizationUsageResponse;
import com.qstory.backend.storyreport.repository.StoryCompletionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IA "기관 관리자 > 이용 현황 관리". DIRECTOR 대시보드가 필요로 하는 최소 지표를 한 방에 반환
 * 하며, 각 카운트는 개별 repository의 count 메서드로 병렬 계산이 아니라 순차 계산 - 지금
 * 카탈로그 규모에서는 이 편이 훨씬 저렴하고 트랜잭션 하나 안에서 일관성도 유지된다.
 */
@Service
public class OrganizationUsageService {

    private static final int RECENT_ACTIVITY_LIMIT = 10;

    private final OrganizationRepository organizationRepository;
    private final OrganizationTutorRepository organizationTutorRepository;
    private final ClassGroupRepository classGroupRepository;
    private final AppUserRepository userRepository;
    private final StoryCompletionRepository completionRepository;

    public OrganizationUsageService(
            OrganizationRepository organizationRepository,
            OrganizationTutorRepository organizationTutorRepository,
            ClassGroupRepository classGroupRepository,
            AppUserRepository userRepository,
            StoryCompletionRepository completionRepository) {
        this.organizationRepository = organizationRepository;
        this.organizationTutorRepository = organizationTutorRepository;
        this.classGroupRepository = classGroupRepository;
        this.userRepository = userRepository;
        this.completionRepository = completionRepository;
    }

    @Transactional(readOnly = true)
    public OrganizationUsageResponse read(CurrentUser caller, UUID organizationId) {
        requireOwnedByCaller(caller, organizationId);

        long tutorCount = organizationTutorRepository.countByOrganization_Id(organizationId);
        long classCount = classGroupRepository.countByOrganization_Id(organizationId);
        long parentCount = userRepository.countByOrganization_IdAndRoleAndDeletedAtIsNull(organizationId, Role.PARENT);
        long classAccountCount = userRepository.countByOrganization_IdAndRoleAndDeletedAtIsNull(organizationId, Role.CLASS_ACCOUNT);
        long completionCount = completionRepository.countByUser_Organization_Id(organizationId);

        List<OrganizationUsageResponse.RecentActivity> recent = completionRepository
                .findByUser_Organization_IdOrderByCompletedAtDesc(
                        organizationId, PageRequest.of(0, RECENT_ACTIVITY_LIMIT))
                .stream()
                .map(completion -> new OrganizationUsageResponse.RecentActivity(
                        completion.getId(),
                        completion.getStoryId(),
                        completion.getUser().getDisplayName(),
                        completion.getCompletedAt()))
                .toList();

        return new OrganizationUsageResponse(
                (int) tutorCount,
                (int) classCount,
                (int) parentCount,
                (int) classAccountCount,
                completionCount,
                recent);
    }

    private Organization requireOwnedByCaller(CurrentUser caller, UUID organizationId) {
        if (caller.orgId() == null || !caller.orgId().equals(organizationId)) {
            throw ApiException.contractError(ErrorCode.FORBIDDEN, "이 기관에 접근할 권한이 없어요.", 403);
        }
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "기관을 찾을 수 없어요.", 404));
    }
}
