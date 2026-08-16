package com.apms.domain.monitoring.model;

import com.apms.common.enums.RelationshipType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "company_relationship_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyRelationshipHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_profile_id", nullable = false)
    private String companyProfileId;

    @Column(name = "source_company_id", nullable = false)
    private String sourceCompanyId;

    @Column(name = "target_company_id", nullable = false)
    private String targetCompanyId;

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

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "proposed_by_account_id", nullable = false)
    private Long proposedByAccountId;

    @Column(name = "approved_by_account_id", nullable = false)
    private Long approvedByAccountId;

    @Column(name = "relationship_change_proposal_id")
    private Long relationshipChangeProposalId;
}
