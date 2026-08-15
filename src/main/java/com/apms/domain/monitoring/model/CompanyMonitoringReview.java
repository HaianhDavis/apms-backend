package com.apms.domain.monitoring.model;

import com.apms.common.enums.MonitoringReviewResult;
import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_monitoring_reviews", indexes = {
        @Index(name = "idx_monitoring_review_assignment", columnList = "monitoring_assignment_id"),
        @Index(name = "idx_monitoring_review_company", columnList = "company_profile_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyMonitoringReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monitoring_assignment_id", nullable = false)
    private CompanyMonitoringAssignment assignment;

    @Column(name = "company_profile_id", nullable = false)
    private String companyProfileId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id", nullable = false)
    private Account reviewedBy;

    @Column(name = "reviewed_at", nullable = false)
    private LocalDateTime reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MonitoringReviewResult result;

    @Column(name = "update_proposal_id")
    private String updateProposalId;

    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String note;
}
