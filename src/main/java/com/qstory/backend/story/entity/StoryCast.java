package com.qstory.backend.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 스토리를 위한 하나의 voice-cast 항목 (예: cast tag "GRETEL" -> speakerId "HG-SPK-GRETEL"). */
@Entity
@Table(name = "story_cast", uniqueConstraints = @UniqueConstraint(columnNames = {"story_id", "cast_tag"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryCast {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    /** 저작 시점의 키, 예: "GRETEL", "OLD_WOMAN" - 런타임에 노출되는 id인 speakerId와는 별개다. */
    @Column(name = "cast_tag", nullable = false)
    private String castTag;

    @Column(nullable = false, unique = true)
    private String speakerId;

    /** CastRole.VALUES 중 하나 - 순수 문자열이며, 그 이유는 해당 클래스의 문서를 참고. */
    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false)
    private String voice;

    @Column(nullable = false, length = 500)
    private String profile;

    @Column(nullable = false, length = 500)
    private String direction;

    private String samePersonKey;
}
