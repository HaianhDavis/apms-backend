package com.apms.domain.project;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_task_submissions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectTaskSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_task_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ProjectTask projectTask;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by_account_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account submittedByAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionType submissionType;

    private String targetEntityType;

    private String targetEntityId;

    private Integer submittedRevisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String note;

    @Column(name = "target_item_ids", columnDefinition = "NVARCHAR(MAX)")
    private String targetItemIds;

    public java.util.List<String> getTargetItemIdList() {
        if (targetItemIds == null || targetItemIds.isEmpty()) return new java.util.ArrayList<>();
        return java.util.Arrays.asList(targetItemIds.split(","));
    }

    public void setTargetItemIdList(java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            this.targetItemIds = null;
        } else {
            this.targetItemIds = String.join(",", ids);
        }
    }

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_account_id", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account reviewedByAccount;

    private LocalDateTime reviewedAt;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String reviewComment;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
