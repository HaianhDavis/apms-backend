package com.apms.domain.audit;

import com.apms.common.enums.AuditAction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_account_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.user.Account actorAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private com.apms.domain.project.Project project;

    public Long getActorAccountId() {
        return actorAccount != null ? actorAccount.getId() : null;
    }

    public Long getProjectId() {
        return project != null ? project.getId() : null;
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(nullable = false)
    private String entityType;

    private String entityId;

    @org.hibernate.annotations.Nationalized
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String detail;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;
}
