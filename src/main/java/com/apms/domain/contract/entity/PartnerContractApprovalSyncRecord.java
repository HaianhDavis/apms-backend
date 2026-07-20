package com.apms.domain.contract.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "partner_contract_approval_syncs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"contract_version_id", "extraction_draft_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerContractApprovalSyncRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extraction_draft_id", nullable = false, length = 50)
    private String extractionDraftId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "contract_version_id", nullable = false)
    private Long contractVersionId;

    @Column(name = "contract_version_number", nullable = false)
    private Integer contractVersionNumber;

    @Column(name = "clause_set_hash", nullable = false, length = 128)
    private String clauseSetHash;

    @Column(name = "status", nullable = false, length = 20)
    private String status; // PENDING, PROCESSING, RETRY, COMPLETED, FAILED

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", length = 50)
    private String lockedBy;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 0;
}
