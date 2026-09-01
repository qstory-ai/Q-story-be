package com.qstory.backend.parent.child.service;

import com.qstory.backend.common.error.ApiException;
import com.qstory.backend.common.error.ErrorCode;
import com.qstory.backend.identity.entity.AppUser;
import com.qstory.backend.identity.repository.AppUserRepository;
import com.qstory.backend.identity.security.CurrentUser;
import com.qstory.backend.parent.child.dto.ChildResponse;
import com.qstory.backend.parent.child.dto.CreateChildRequest;
import com.qstory.backend.parent.child.dto.UpdateChildRequest;
import com.qstory.backend.parent.child.entity.Child;
import com.qstory.backend.parent.child.repository.ChildRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학부모(PARENT)가 등록한 아이 프로필의 CRUD. TutorStudentService의 CRUD 절반을 그대로 미러링
 * 한다 - 소유권은 항상 caller.userId()로만 판단하고, 다른 부모의 아이는 존재조차 노출하지 않기
 * 위해 접근 실패 시 403이 아닌 404로 응답한다(TutorStudentService.requireOwnedStudent 참조).
 */
@Service
public class ChildService {

    /** UI/DB 양쪽 상한을 맞춘 값 - 마이그레이션의 varchar(40)과 동일. */
    private static final int MAX_NAME_LENGTH = 40;
    private static final int MAX_AGE_BAND_LENGTH = 20;
    private static final int MAX_AVATAR_KEY_LENGTH = 32;
    private static final int MAX_GENDER_LENGTH = 8;

    private final ChildRepository childRepository;
    private final AppUserRepository appUserRepository;

    public ChildService(ChildRepository childRepository, AppUserRepository appUserRepository) {
        this.childRepository = childRepository;
        this.appUserRepository = appUserRepository;
    }

    public List<ChildResponse> listMine(CurrentUser caller) {
        return childRepository.findByParent_IdOrderByCreatedAtAsc(caller.userId()).stream()
                .map(ChildResponse::of)
                .toList();
    }

    @Transactional
    public ChildResponse create(CurrentUser caller, CreateChildRequest request) {
        String name = requireField(request.name(), MAX_NAME_LENGTH, "아이 이름 또는 별명을 입력해 주세요.");
        String ageBand = requireField(request.ageBand(), MAX_AGE_BAND_LENGTH, "연령대를 선택해 주세요.");
        String avatarKey = requireField(request.avatarKey(), MAX_AVATAR_KEY_LENGTH, "아바타를 선택해 주세요.");
        String gender = optionalField(request.gender(), MAX_GENDER_LENGTH, "성별 값이 올바르지 않아요.");

        AppUser parent = appUserRepository.getReferenceById(caller.userId());
        Instant now = Instant.now();
        Child child = childRepository.save(Child.builder()
                .parent(parent)
                .name(name)
                .ageBand(ageBand)
                .avatarKey(avatarKey)
                .gender(gender)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return ChildResponse.of(child);
    }

    @Transactional
    public ChildResponse update(CurrentUser caller, UUID childId, UpdateChildRequest request) {
        Child child = requireOwn(caller, childId);
        if (request.name() != null) child.setName(requireField(request.name(), MAX_NAME_LENGTH, "아이 이름 또는 별명을 입력해 주세요."));
        if (request.ageBand() != null) child.setAgeBand(requireField(request.ageBand(), MAX_AGE_BAND_LENGTH, "연령대를 선택해 주세요."));
        if (request.avatarKey() != null) child.setAvatarKey(requireField(request.avatarKey(), MAX_AVATAR_KEY_LENGTH, "아바타를 선택해 주세요."));
        // gender는 빈 문자열로 "지우기"를 허용한다 - null이 온 경우는 "값 미포함(그대로)"으로 해석.
        if (request.gender() != null) child.setGender(optionalField(request.gender(), MAX_GENDER_LENGTH, "성별 값이 올바르지 않아요."));
        child.setUpdatedAt(Instant.now());
        return ChildResponse.of(childRepository.save(child));
    }

    @Transactional
    public void delete(CurrentUser caller, UUID childId) {
        Child child = requireOwn(caller, childId);
        childRepository.delete(child);
    }

    private Child requireOwn(CurrentUser caller, UUID childId) {
        return childRepository.findByIdAndParent_Id(childId, caller.userId())
                .orElseThrow(() -> ApiException.contractError(ErrorCode.NOT_FOUND, "아이 프로필을 찾을 수 없어요.", 404));
    }

    private static String requireField(String value, int maxLength, String errorMessage) {
        if (value == null || value.isBlank()) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, errorMessage);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, errorMessage);
        }
        return trimmed;
    }

    private static String optionalField(String value, int maxLength, String errorMessage) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength) {
            throw ApiException.contractError(ErrorCode.VALIDATION_FAILED, errorMessage);
        }
        return trimmed;
    }
}
