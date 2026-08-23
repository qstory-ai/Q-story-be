package com.qstory.backend.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A registered story, e.g. "HG" (Hansel & Gretel). Content-authoring data, not runtime telemetry. */
@Entity
@Table(name = "story")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Story {

    /** Stable, human-assigned content id (e.g. "HG") - not a surrogate key. */
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String contentVersion;

    /** One of StoryAvailability.VALUES - plain string, see that class's doc for why. */
    @Column(nullable = false)
    private String availability;

    @Column(nullable = false)
    private String routePromptVersion;

    @Column(nullable = false)
    private String routePolicyVersion;

    @Column(nullable = false)
    private String responseTextNormalizationVersion;

    @Column(nullable = false)
    private String castVersion;

    /**
     * False (default) means this story is free regardless of the caller's organization -
     * "HG" stays free by construction, not by a special case in the entitlement gate (see
     * com.qstory.backend.entitlement.service.EntitlementService). True means an authenticated
     * caller whose organization lacks an active subscription is denied.
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean requiresEntitlement = false;

    /**
     * Leftover fields from the imported story-package.generated.json/generated-story-content.json
     * that don't have a dedicated table ({@code source}, {@code reportCopy}, {@code release},
     * {@code evaluation} - see StoryImportService/StoryContentAssemblyService). Null until the
     * story has been imported via POST /v1/admin/stories/import at least once.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> packageExtras;
}
