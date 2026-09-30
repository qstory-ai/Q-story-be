package com.qstory.backend.parent.child.entity;

import com.qstory.backend.common.util.ChildAge;
import com.qstory.backend.identity.entity.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

/**
 * 학부모(PARENT)가 등록한 아이 프로필. 한 부모가 여러 아이를 관리하므로 1:N이며, "아이 선택기"와
 * 아이별 리포트가 이 행의 id를 기준으로 동작한다.
 *
 * <p>AppUser가 삭제되면 함께 지워진다({@code ON DELETE CASCADE}) - 계정 없이 남는 아이 프로필은
 * 존재 이유가 없기 때문. 반대로 아이 삭제가 부모 계정에 영향을 주는 일은 없다.
 */
@Entity
@Table(name = "parent_child")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Child {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser parent;

    @Column(nullable = false)
    private String name;

    /**
     * 저장 시점에 계산된 연령대 구간. birthYear가 있으면 응답마다 {@link #currentAgeBand()}로 다시
     * 계산하므로 해가 바뀌어도 낡지 않는다. 출생연도가 없는 예전 행만 이 값을 그대로 쓴다.
     */
    @Column(name = "age_band", nullable = false)
    private String ageBand;

    /** ~년생(051). 나이는 ChildAge가 연 나이로 계산한다. 예전 행은 null. */
    @Column(name = "birth_year")
    private Integer birthYear;

    /**
     * 프리셋 아바타 식별자 - 프리셋 파일들은 FE에 상수로 존재하고 서버는 해당 키만 저장한다.
     * 커스텀 이미지 업로드는 아직 지원 범위 밖.
     */
    @Column(name = "avatar_key", nullable = false)
    private String avatarKey;

    /** 선택 - 지금 리포트/큐레이션에는 쓰이지 않고 프로필 화면에서만 표시한다. */
    private String gender;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public String currentAgeBand() {
        return birthYear == null ? ageBand : ChildAge.parentBand(birthYear);
    }
}
