package com.qstory.backend.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The route policy handed to the LLM, stored under the version label a story names in its
 * route-context.yaml.
 *
 * <p>The label used to travel into the DB with the story while the text it named was a String.join
 * inside OpenRouterClient - so editing the policy took a redeploy and never moved the version, and
 * bumping the version never moved the policy. They are written together now.
 *
 * <p>Not tied to a story: several stories can name the same version.
 */
@Entity
@Table(name = "route_prompt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutePrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String version;

    /** Space-joined lines forming the system message. The version line is prepended at request time. */
    @Column(name = "system_text", nullable = false, columnDefinition = "text")
    private String systemText;

    /** Space-joined lines forming the request payload's "instruction" field. */
    @Column(name = "instruction_text", nullable = false, columnDefinition = "text")
    private String instructionText;
}
