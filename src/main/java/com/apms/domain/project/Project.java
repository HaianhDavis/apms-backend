package com.apms.domain.project;

import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String projectName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectType projectType;

    /**
     * Nullable. Required only when projectType = UPDATE_EXISTING_COMPANY.
     * Stores the UUID (companyId) of the target CompanyProfile in MongoDB.
     */
    @Column(nullable = true)
    private String targetCompanyProfileId;

    /**
     * Always required. For UPDATE_EXISTING_COMPANY: copied from the selected profile.
     * For RESEARCH_NEW_COMPANY: a single company name.
     * For RESEARCH_MULTIPLE_COMPANIES: a research scope/query string.
     */
    @Column(nullable = false)
    private String targetCompanyName;

    /**
     * The official business relationship type for the target company.
     * Selected by the Manager at project creation time.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_relationship_type", nullable = true, length = 50)
    private com.apms.common.enums.RelationshipType targetRelationshipType;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.DRAFT;

    /**
     * Account (SQL) who created this project.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.user.Account createdByAccount;

    public Long getCreatedById() {
        return createdByAccount != null ? createdByAccount.getId() : null;
    }

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProjectMember> members = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
