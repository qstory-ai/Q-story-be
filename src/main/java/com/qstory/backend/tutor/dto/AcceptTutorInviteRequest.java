package com.qstory.backend.tutor.dto;

/**
 * 비로그인 상태로 초대를 수락하면 email/password/displayName으로 새 PARENT 계정을 만든다(회원가입을
 * 겸함). 이미 로그인된 PARENT가 수락하면 이 필드들은 전부 무시된다 - 호출자의 기존 계정에 바로 연결된다.
 */
public record AcceptTutorInviteRequest(String loginId, String email, String password, String displayName) {}
