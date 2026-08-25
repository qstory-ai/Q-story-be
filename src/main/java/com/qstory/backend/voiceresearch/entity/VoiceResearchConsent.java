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

/** 옵트인 음성 연구 녹음에 대한 보호자 동의. id는 클라이언트에서 생성된다. */
@Entity
@Table(name = "voice_research_consents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoiceResearchConsent {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String deletionTokenHash;

    @Column(nullable = false)
    private String consentVersion;

    @Column(nullable = false)
    @Builder.Default
    private String consentMethod = "parent_checkbox";

    @Column(nullable = false)
    private Instant consentedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
