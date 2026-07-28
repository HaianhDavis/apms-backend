package com.apms.domain.project;

import com.apms.common.enums.TaskStatus;
import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Work in progress saved by the assignee before it is formally submitted. */
@Entity
@Table(name = "project_task_drafts", uniqueConstraints = @UniqueConstraint(columnNames = {"project_task_id", "staff_account_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectTaskDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_task_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ProjectTask projectTask;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_account_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account staffAccount;

    /** Mongo CompanyProfile id selected by the staff member. */
    @Column(length = 100)
    private String attachedCompanyProfileId;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String note;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
