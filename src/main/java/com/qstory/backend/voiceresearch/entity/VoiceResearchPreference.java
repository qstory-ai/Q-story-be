package com.qstory.backend.voiceresearch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 보호자 계정 단위 음성 연구 동의 상태(마이페이지에서 켜고 끈다). 행이 없으면
 * VoiceResearchService.DEFAULT_ENABLED를 따른다 - db/schema/057-voice-research-account-consent.sql 참고.
 */
@Entity
@Table(name = "voice_research_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoiceResearchPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private boolean enabled;

    /** 마지막으로 동의한 약관 버전. 한 번도 켠 적이 없으면 null. */
    private String consentVersion;

    /** 마지막으로 동의한 시각. 한 번도 켠 적이 없으면 null. */
    private Instant consentedAt;

    /** 마지막으로 철회한 시각. 다시 동의하면 null. */
    private Instant withdrawnAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
