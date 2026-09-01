package com.qstory.backend.parent.child.repository;

import com.qstory.backend.parent.child.entity.Child;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildRepository extends JpaRepository<Child, UUID> {

    List<Child> findByParent_IdOrderByCreatedAtAsc(UUID parentId);

    Optional<Child> findByIdAndParent_Id(UUID id, UUID parentId);

    long countByParent_Id(UUID parentId);
}
