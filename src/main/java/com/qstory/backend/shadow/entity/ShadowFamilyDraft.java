package com.qstory.backend.shadow.entity;

import com.qstory.backend.common.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * ShadowIntentCandidate 하나에 대해 ShadowFamilyGenerationService가 LLM+이미지+TTS로 미리 만들어 둔
 * 15번째 family 초안. candidate당 하나만 유지한다(재생성하면 덮어씀). imageObjectName/audioObjectName은
 * AppProperties.Supabase.shadowAssetsBucket(비공개)의 오브젝트 경로이고, 실제 URL은 요청마다
 * SupabaseStorageClient.createSignedUrl()로 짧게 서명해서 내려준다 - 여기에 영구 URL을 저장하지 않는다.
 *
 * <p>제품 결정: reviewStatus는 사람 검수 없이 생성 시점에 바로 APPROVED로 저장된다(자동 LLM
 * 검증 게이트만 통과하면 됨) - ReviewStatus 클래스 코멘트의 "사람 없이는 자동 진행 안 함" 원칙에서
 * 벗어나는 의도적 예외다. reviewNote에 그 사실을 남긴다.
 */
@Entity
@Table(name = "shadow_family_drafts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowFamilyDraft {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ShadowIntentCandidate candidate;

    @Column(nullable = false)
    private String proposedFamilyId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 160)
    private String intentSummary;

    @Column(nullable = false, length = 300)
    private String rationale;

    @Column(nullable = false, length = 120)
    private String acknowledgementText;

    @Column(nullable = false, length = 200)
    private String entryState;

    @Column(nullable = false, length = 200)
    private String exitState;

    @Column(nullable = false)
    private String rejoinAnchorId;

    @Column(nullable = false, length = 220)
    private String reportSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> choiceCopy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> beats;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> imageBrief;

    @Column(nullable = false)
    private String imageObjectName;

    @Column(nullable = false)
    private String audioObjectName;

    @Column(nullable = false)
    private String imageMimeType;

    @Column(nullable = false)
    private String audioMimeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus reviewStatus;

    @Column(nullable = false)
    private String promptVersion;

    @Column(nullable = false)
    private String llmModel;

    @Column(nullable = false)
    private String imageModel;

    @Column(nullable = false)
    private String ttsModel;

    @Column(nullable = false)
    private Instant generatedAt;

    private Instant reviewedAt;

    @Column(length = 1000)
    private String reviewNote;
}
