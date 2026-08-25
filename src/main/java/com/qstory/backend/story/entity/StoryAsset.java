package com.qstory.backend.story.entity;

import com.qstory.backend.common.enums.AssetCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 스토리에 속한 하나의 삽화 또는 내레이션 클립.
 *
 * <p>대체 키(surrogate key)는 단순한 identity long이다: 이 테이블은 대량으로(단일 스토리당 256개)
 * join되고 참조되는 유일한 스토리 테이블이며, DB 외부에서 이 값을 추측해야 할 필요가 전혀 없다.
 * 콘텐츠 작성자가 실제로 쓰는 것은 {@link #slug}이며, 사람이 읽을 수 있게 유지되므로 스토리 스크립트는
 * diff에서 아무도 알아볼 수 없는 숫자 대신 {@code asset=home-table}처럼 쓸 수 있다.
 */
@Entity
@Table(
        name = "story_asset",
        uniqueConstraints = @UniqueConstraint(columnNames = {"story_id", "slug"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "story_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Story story;

    /** 저작 시점의 이름으로, 스토리 안에서 고유하다, 예: "home-table" 또는 "a-observe-bird-01". */
    @Column(nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AssetCategory category;

    /** 스토리의 에셋 루트 아래의 경로, 예: "illustrations/hg-art-01-home-table.jpg". */
    @Column(nullable = false)
    private String file;

    /** 파일의 서브리소스 무결성(Subresource-integrity) 해시로, `npm run content:fix`에 의해 재계산된다. */
    @Column(nullable = false)
    private String integrity;

    /** BRANCH_ART와 BRIDGE에 대해서만 설정된다 - 에셋이 속한 action family. */
    @Column(name = "family_id")
    private String familyId;

    /** family의 branch art 안에서 1부터 시작하는 panel 인덱스; BRANCH_ART가 아니면 null. */
    private Integer panel;

    /** 이 파일이 런타임에 다시 렌더링된 시점; 빌드에 포함되어 배포된 파일이라면 null. */
    @Column(name = "rendered_at")
    private java.time.Instant renderedAt;

    /** 재렌더링에 사용된 cast voice로, 이후 cast가 바뀌었는지를 비교를 통해 감지할 수 있게 해준다. */
    @Column(name = "rendered_voice", length = 64)
    private String renderedVoice;
}
