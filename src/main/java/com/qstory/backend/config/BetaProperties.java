package com.qstory.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 베타 운영 스위치. openAccessTutorOrg가 켜져 있으면 선생님·관리자 계정에 결제 없이 모든 이야기를 연다 -
 * 보호자는 그대로 이용권이 필요하다. 정식 출시 때 BETA_OPEN_ACCESS_TUTOR_ORG=false로 끈다.
 */
@ConfigurationProperties(prefix = "qstory.beta")
public record BetaProperties(boolean openAccessTutorOrg) {}
