package com.apms.domain.profile.closeness;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_relationship_closeness", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"owner_company_profile_id", "target_company_profile_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CompanyRelationshipCloseness {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_company_profile_id", nullable = false, length = 255)
    private String ownerCompanyProfileId;

    @Column(name = "target_company_profile_id", nullable = false, length = 255)
    private String targetCompanyProfileId;

    @Column(name = "stars", nullable = false)
    private Integer stars;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "rated_by_account_id", nullable = false)
    private Long ratedByAccountId;

    @Column(name = "rated_by_role", length = 80)
    private String ratedByRole;

    @Column(name = "manager_stars")
    private Integer managerStars;

    @Column(name = "manager_note", length = 1000)
    private String managerNote;

    @Column(name = "manager_rated_by_account_id")
    private Long managerRatedByAccountId;

    @Column(name = "manager_rated_at")
    private LocalDateTime managerRatedAt;

    @Column(name = "owner_stars")
    private Integer ownerStars;

    @Column(name = "owner_note", length = 1000)
    private String ownerNote;

    @Column(name = "owner_rated_by_account_id")
    private Long ownerRatedByAccountId;

    @Column(name = "owner_rated_at")
    private LocalDateTime ownerRatedAt;

    @CreationTimestamp
    @Column(name = "rated_at", nullable = false, updatable = false)
    private LocalDateTime ratedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
