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

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.DRAFT;

    /**
     * ID of the User (SQL) who created this project.
     */
    @Column(nullable = false)
    private Long createdBy;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ProjectMember> members = new ArrayList<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
