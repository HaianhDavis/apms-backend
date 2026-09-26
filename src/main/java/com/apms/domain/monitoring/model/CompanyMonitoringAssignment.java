package com.apms.domain.monitoring.model;

import com.apms.common.enums.MonitoringFrequency;
import com.apms.common.enums.MonitoringStatus;
import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_monitoring_assignments", indexes = {
        @Index(name = "idx_monitoring_company", columnList = "company_profile_id", unique = true),
        @Index(name = "idx_monitoring_staff", columnList = "assigned_staff_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyMonitoringAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_profile_id", nullable = false)
    private String companyProfileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_staff_id", nullable = false)
    private Account assignedStaff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_manager_id", nullable = false)
    private Account assignedByManager;

    @Enumerated(EnumType.STRING)
    @Column(nullable = true)
    private MonitoringFrequency frequency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MonitoringStatus status;

    @Column(name = "last_reviewed_at")
    private LocalDateTime lastReviewedAt;

    @Column(name = "next_review_at", nullable = true)
    private LocalDateTime nextReviewAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
