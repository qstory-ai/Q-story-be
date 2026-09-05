package com.qstory.backend.languagepolicy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 아이의 언어 규칙 (스토리 제작 규약 §2.4) - {@code scope}는 지금은 항상 "GLOBAL"이다(전 스토리
 * 공용). 스토리별 예외가 필요해지면 scope에 storyId를 쓰는 행을 추가로 두는 방식으로 확장할 수
 * 있게 Story에 대한 FK 없이 순수 문자열로 둔다 - GLOBAL은 어떤 스토리도 가리키지 않기 때문에
 * FK를 걸 수도 없다(StoryVisualReferencePack이 부트스트랩 순서 문제로 FK를 뺀 것과는 다른 이유).
 */
@Entity
@Table(name = "language_policy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LanguagePolicy {

    /** "GLOBAL" 또는 (아직 미구현인) storyId. */
    @Id
    private String scope;

    @Column(nullable = false)
    private int maxSentenceLength;

    @Column(nullable = false)
    private int recommendedMin;

    @Column(nullable = false)
    private int recommendedMax;

    @Column(nullable = false, length = 300)
    private String onomatopoeiaPolicy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<LanguageBannedWord> bannedWords = List.of();
}
