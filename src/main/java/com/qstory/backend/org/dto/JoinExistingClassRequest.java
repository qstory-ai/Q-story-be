package com.qstory.backend.org.dto;

/**
 * 이미 Q-Story 학부모 계정이 있는 사람이 기관 반에 합류할 때 쓰는 요청이다.
 *
 * <p>반 코드는 재사용 가능하고 초대 토큰은 1회용이다. 둘 중 정확히 하나만 받아야 한다.
 * 로그인된 사용자의 식별 정보는 JWT에서 얻으므로 회원가입 필드는 포함하지 않는다.</p>
 */
public record JoinExistingClassRequest(
        String classCode, String inviteToken, Boolean replaceExisting,
        /** 선생님이 운영하는 반에 연결할 때만 필요 - 그 반의 학생 명단에 올라갈 아이의 이름·출생연도. */
        String childName, Integer childBirthYear) {

    /** replaceExisting이 true면 이미 속한 반에서 새 반으로 한 번에 옮긴다(연결 해제 후 재가입과 같다). */
    public boolean replacing() {
        return Boolean.TRUE.equals(replaceExisting);
    }
}
