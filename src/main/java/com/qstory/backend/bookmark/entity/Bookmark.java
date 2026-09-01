package com.qstory.backend.bookmark.entity;

import com.qstory.backend.identity.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

/**
 * 부모/선생님 모두가 쓰는 "저장한 작품" 북마크 한 건. IA "[2] 서재 > 저장한 작품"과 선생님용
 * "내 서재 > 저장한 작품"이 같은 저장소를 공유하므로 role 구분이 필요 없다 - 소유는 언제나
 * 로그인한 계정(AppUser).
 *
 * <p>story_id는 varchar로만 남긴다 - story_completion과 같은 규약. story 테이블에 없는 콘텐츠
 * (예: 아직 시드 전 이야기, 회수된 이야기)를 참조해도 저장 자체는 유지되며, 클라이언트가
 * story catalog에서 지운 storyId를 만나면 조용히 필터한다.
 */
@Entity
@Table(name = "bookmark", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "story_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Bookmark {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    @Column(name = "story_id", nullable = false, length = 64)
    private String storyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
