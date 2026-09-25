package com.apms.domain.project;

import com.apms.common.enums.TaskPriority;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "key_result_id", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ProjectKeyResult keyResult;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_account_id", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account assignedToAccount;

    @org.hibernate.annotations.Nationalized
    @Column(nullable = false, length = 255)
    private String title;

    @org.hibernate.annotations.Nationalized
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TaskStatus status = TaskStatus.TODO;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", length = 50)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    private TaskPriority priority;

    private LocalDateTime dueDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_account_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account createdByAccount;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @Column(nullable = true, length = 36)
    private String targetCompanyProfileId;
}
