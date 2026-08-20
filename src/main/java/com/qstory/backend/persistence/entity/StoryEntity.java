package com.qstory.backend.persistence.entity;

import com.qstory.backend.common.enums.StoryAvailability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A registered story, e.g. "HG" (Hansel & Gretel). Content-authoring data, not runtime telemetry. */
@Entity
@Table(name = "story")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryEntity {

    /** Stable, human-assigned content id (e.g. "HG") - not a surrogate key. */
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String contentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StoryAvailability availability;

    @Column(nullable = false)
    private String routePromptVersion;

    @Column(nullable = false)
    private String routePolicyVersion;

    @Column(nullable = false)
    private String responseTextNormalizationVersion;

    @Column(nullable = false)
    private String castVersion;
}
