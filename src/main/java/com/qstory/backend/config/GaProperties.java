package com.qstory.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GA4 일일 집계 가져오기 설정(qstory.ga). serviceAccountJson은 서비스 계정 키 JSON 원문 또는 그 base64
 * (GA_SERVICE_ACCOUNT_JSON), propertyId는 숫자 속성 ID(GA_PROPERTY_ID, 여러 개면 쉼표로 - 랜딩과 웹·앱 속성). 둘 중 하나라도 비었거나 importEnabled가
 * false(QSTORY_GA_IMPORT_ENABLED)면 가져오기는 아무것도 하지 않는다.
 */
@ConfigurationProperties(prefix = "qstory.ga")
public record GaProperties(
        String serviceAccountJson,
        String propertyId,
        Boolean importEnabled,
        Integer lookbackDays,
        Integer initialBackfillDays) {

    public boolean enabled() {
        return !Boolean.FALSE.equals(importEnabled) && notBlank(serviceAccountJson) && notBlank(propertyId);
    }

    public int lookbackDaysOrDefault() {
        return lookbackDays == null || lookbackDays < 1 ? 3 : Math.min(lookbackDays, 30);
    }

    public int initialBackfillDaysOrDefault() {
        return initialBackfillDays == null || initialBackfillDays < 1 ? 30 : Math.min(initialBackfillDays, 365);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
