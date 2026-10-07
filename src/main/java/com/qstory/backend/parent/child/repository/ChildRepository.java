package com.qstory.backend.parent.child.repository;

import com.qstory.backend.parent.child.entity.Child;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChildRepository extends JpaRepository<Child, UUID> {

    List<Child> findByParent_IdOrderByCreatedAtAsc(UUID parentId);

    Optional<Child> findByIdAndParent_Id(UUID id, UUID parentId);

    /**
     * 회원 탈퇴 - 아이 프로필은 가입 기록(id·parent_id·created_at·age_band)만 남기고 식별 정보를 지운다.
     * age_band는 not null이라 그대로 둔다.
     */
    @Modifying
    @Query("update Child c set c.name = :name, c.birthYear = null, c.gender = null, c.avatarKey = :avatarKey, "
            + "c.updatedAt = :now where c.parent.id = :parentId")
    int maskAllOfParent(@Param("parentId") UUID parentId, @Param("name") String name,
            @Param("avatarKey") String avatarKey, @Param("now") Instant now);
}
