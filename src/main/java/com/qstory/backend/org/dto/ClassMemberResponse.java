package com.qstory.backend.org.dto;

import com.qstory.backend.identity.entity.AppUser;
import java.time.Instant;
import java.util.UUID;

/**
 * 반에 소속된 사용자 요약. IA "반/학생 관리 > 반 상세"에서 반에 참여한 부모(그리고 미래에는
 * 반 자체 계정)를 목록으로 볼 때 쓴다. childName은 PARENT에게만 의미가 있는 필드지만, 반 상세
 * 화면에서 "누구의 부모인지"를 함께 보여 주려고 그대로 노출한다.
 */
public record ClassMemberResponse(
        UUID id, String displayName, String email, String childName, Instant joinedAt) {

    public static ClassMemberResponse of(AppUser user) {
        return new ClassMemberResponse(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.getChildName(),
                user.getCreatedAt());
    }
}
