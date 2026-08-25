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

/** 등록된 스토리, 예: "HG" (Hansel & Gretel). 런타임 텔레메트리가 아니라 콘텐츠 저작 데이터다. */
@Entity
@Table(name = "story")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Story {

    /** 사람이 부여한 안정적인 콘텐츠 id (예: "HG") - 대체 키(surrogate key)가 아니다. */
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String contentVersion;

    /** StoryAvailability.VALUES 중 하나 - 순수 문자열이며, 그 이유는 해당 클래스의 문서를 참고. */
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
     * false(기본값)는 호출자의 조직과 무관하게 이 스토리가 무료임을 의미한다 - "HG"는 entitlement
     * 게이트(com.qstory.backend.entitlement.service.EntitlementService 참고) 안의 특수 케이스가
     * 아니라 구조적으로 무료로 유지된다. true는 활성 구독이 없는 조직에 속한 인증된 호출자가
     * 거부됨을 의미한다.
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean requiresEntitlement = false;

    /**
     * 임포트된 story-package.generated.json/generated-story-content.json에서 전용 테이블이 없는
     * 나머지 필드들 ({@code source}, {@code reportCopy}, {@code release}, {@code evaluation} -
     * StoryImportService/StoryContentAssemblyService 참고). 스토리가 POST
     * /v1/admin/stories/import를 통해 최소 한 번 임포트되기 전까지는 null이다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> packageExtras;

    /**
     * 이 스토리의 커버 아트 경로로, fe/q-story-web 자체의 public/ 폴더를 기준으로 한 상대 경로다
     * (백엔드나 CDN에 호스팅되는 에셋이 아니다 - 이 제품은 아직 이미지 파이프라인이 없으며,
     * 006-story-catalog-metadata.sql 참고). 관리자가 설정하기 전까지는 null이며, 홈 화면의
     * 라이브러리/상세 페이지는 이를 무리 없이 처리해야 한다.
     */
    private String coverImageUrl;

    /** 홈 화면 라이브러리 카드와 스토리 상세 페이지에 표시되는 짧은 한국어 소개문. 생략해도 null-safe하다. */
    @Column(length = 1000)
    private String description;

    /** 라이브러리/상세 페이지에 pill 형태로 표시되는 자유 텍스트 카탈로그 분류 (예: "고전동화"). 생략해도 null-safe하다. */
    private String category;
}
