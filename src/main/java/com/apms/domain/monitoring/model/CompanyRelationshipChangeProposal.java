package com.apms.domain.monitoring.model;

import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.RelationshipChangeStatus;
import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_relationship_change_proposals")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyRelationshipChangeProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_profile_id", nullable = false)
    private String companyProfileId;

    @Column(name = "monitoring_assignment_id", nullable = false)
    private Long monitoringAssignmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_relationship_type", nullable = false)
    private RelationshipType oldRelationshipType;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_relationship_type", nullable = false)
    private RelationshipType newRelationshipType;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposed_by_account_id", nullable = false)
    private Account proposedByAccount;

    @Column(name = "proposed_at", nullable = false)
    private LocalDateTime proposedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RelationshipChangeStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_account_id")
    private Account reviewedByAccount;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reject_reason", length = 2000)
    private String rejectReason;
}
