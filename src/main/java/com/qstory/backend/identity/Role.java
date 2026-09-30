package com.qstory.backend.identity;

/**
 * StoryAvailability/CastRole/TrafficType과 달리 실제 enum으로 유지한다: 인가(authorization)
 * 코드가 이 값으로 분기하기 때문이다.
 *
 * <p>DIRECTOR/PARENT/TUTOR는 공개 회원가입 엔드포인트를 통해 얻을 수 있는 셀프서비스
 * 고객 역할이다. STAFF는 StoryAuthoringController와 NarrationRerenderController가 검사하는
 * 내부 콘텐츠 제작 역할로 - 고객의 셀프 회원가입으로는 절대 도달할 수 없도록 의도적으로
 * 막혀 있다; 이를 발급하는 유일한 방법은 POST /v1/auth/signup/staff이며, 이 엔드포인트 자체도
 * POST /v1/admin/stories/import와 동일한 X-Admin-Token 공유 비밀값으로 게이트되어 있다
 * (AuthController 참고). 고객 대상 역할로 내부 운영자 전용 엔드포인트를 게이트하지 말 것 - 셀프 가입한
 * 누구나 스토리를 수정하고 유료 TTS 재생성을 트리거할 수 있게 된다.
 */
public enum Role {
    DIRECTOR,
    PARENT,
    TUTOR,
    STAFF
}
