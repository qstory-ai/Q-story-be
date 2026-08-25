package com.qstory.backend.story.entity;

import com.qstory.backend.common.enums.RevisionOperation;
import com.qstory.backend.common.enums.RevisionTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 추가(append)된 하나의 저작(authoring) 편집 기록.
 *
 * <p>스토리 콘텐츠는 git으로 추적되던 콘텐츠 파일에서 이 데이터베이스로 옮겨가고 있는데, 그렇게 하지
 * 않으면 git이 조용히 제공해 주던 세 가지 - 누가 바꿨는지, 이전에는 어떤 모습이었는지, 어떻게
 * 되돌릴 수 있는지 - 를 잃게 된다. 모든 저작 write는 대상의 이전 상태 전체를 담은 행을 여기에
 * 추가하므로, 이력을 읽을 수 있고 되돌리기(revert)는 예전 스냅샷을 재생하는 일이 된다.
 */
@Entity
@Table(name = "story_revision")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "story_id", nullable = false)
    private String storyId;

    /** 스토리별로 단조 증가한다; 편집기가 다시 보내는 낙관적 동시성(optimistic-concurrency) 토큰이기도 하다. */
    @Column(nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private RevisionTarget targetType;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RevisionOperation operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    /** content:import 파이프라인의 경우 null이며, 이는 사람이 아니라 시스템으로서 동작하기 때문이다. */
    @Column(name = "author_id")
    private UUID authorId;

    @Column(length = 500)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
