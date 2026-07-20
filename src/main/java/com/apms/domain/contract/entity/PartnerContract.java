package com.apms.domain.contract.entity;

import com.apms.domain.contract.enums.ContractLifecycleStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_contracts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String referenceCompanyId;

    @Column(nullable = false, length = 36)
    private String partnerCompanyId;

    @Column(nullable = false)
    private Long sourceProjectId;

    @Column(nullable = true)
    private Long sourceTaskId;

    @Column(nullable = true, length = 50)
    private String rawDocumentId;

    @Column(nullable = true, length = 100)
    private String contractNumber;

    @Column(nullable = true, length = 255)
    private String contractTitle;

    @Column(nullable = true, length = 100)
    private String contractType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ContractReviewStatus reviewStatus = ContractReviewStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = true, length = 30)
    private ContractLifecycleStatus lifecycleStatus;

    @Column(nullable = true)
    private LocalDate signedDate;

    @Column(nullable = true)
    private LocalDate effectiveDate;

    @Column(nullable = true)
    private LocalDate expiryDate;

    @Column(nullable = true, length = 3)
    private String currency;

    @Column(nullable = true, precision = 18, scale = 2)
    private BigDecimal totalContractValue;

    @Column(nullable = false)
    @Builder.Default
    private Integer currentVersion = 0;

    @Column(nullable = true, length = 50)
    private String pendingExtractionId;

    @Column(nullable = true, length = 128)
    private String pendingClauseSetHash;

    @Column(nullable = false, updatable = false)
    private Long createdByAccountId;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = true)
    private Long updatedByAccountId;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(nullable = true)
    private Long approvedByAccountId;

    @Column(nullable = true)
    private LocalDateTime approvedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 0;
}
