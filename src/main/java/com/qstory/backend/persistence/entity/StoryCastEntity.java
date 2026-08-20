package com.qstory.backend.persistence.entity;

import com.qstory.backend.common.enums.CastRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;

/** One voice-cast entry (e.g. cast tag "GRETEL" -> speakerId "HG-SPK-GRETEL") for a story. */
@Entity
@Table(name = "story_cast", uniqueConstraints = @UniqueConstraint(columnNames = {"story_id", "cast_tag"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryCastEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private StoryEntity story;

    /** Authoring-time key, e.g. "GRETEL", "OLD_WOMAN" - distinct from speakerId, which is the runtime-facing id. */
    @Column(name = "cast_tag", nullable = false)
    private String castTag;

    @Column(nullable = false, unique = true)
    private String speakerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CastRole role;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String voice;

    @Column(nullable = false, length = 500)
    private String profile;

    @Column(nullable = false, length = 500)
    private String direction;

    /** Links two cast entries that are the same underlying character (e.g. OLD_WOMAN and WITCH). */
    private String samePersonKey;
}
