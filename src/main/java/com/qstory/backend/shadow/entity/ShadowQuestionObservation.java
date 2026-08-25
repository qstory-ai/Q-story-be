package com.qstory.backend.shadow.entity;
import com.qstory.backend.betaevents.entity.StorySession;
import com.qstory.backend.betaevents.entity.StoryEvent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/** story_events의 한 행(라우팅된 질문)을 그것이 반영된 shadow intent candidate와 연결한다. */
@Entity
@Table(name = "shadow_question_observations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowQuestionObservation {

    @Id
    @UuidGenerator
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private StoryEvent event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ShadowIntentCandidate candidate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private StorySession session;

    @Column(nullable = false)
    private Instant observedAt;
}
