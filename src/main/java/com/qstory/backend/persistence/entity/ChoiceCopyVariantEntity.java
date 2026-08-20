package com.qstory.backend.persistence.entity;

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

/** One of the 3 reviewer-authored label/meaning variants offered for a THREE_PATHS option on this family. */
@Entity
@Table(name = "choice_copy_variant", uniqueConstraints = @UniqueConstraint(columnNames = {"family_id", "variant_index"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChoiceCopyVariantEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private StoryActionFamilyEntity family;

    @Column(name = "variant_index", nullable = false)
    private int variantIndex;

    @Column(nullable = false, length = 30)
    private String label;

    @Column(nullable = false, length = 120)
    private String meaning;
}
