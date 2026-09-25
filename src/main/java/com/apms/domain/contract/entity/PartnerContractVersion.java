package com.apms.domain.contract.entity;

import com.apms.domain.contract.enums.ContractLifecycleStatus;
import com.apms.domain.contract.enums.ContractReviewStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_contract_versions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"contract_id", "version"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerContractVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

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

    @org.hibernate.annotations.Nationalized
    @Column(nullable = true, length = 255)
    private String contractTitle;

    @org.hibernate.annotations.Nationalized
    @Column(nullable = true, length = 100)
    private String contractType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ContractReviewStatus reviewStatus;

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

    @Column(nullable = false, updatable = false)
    private Long createdByAccountId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = true)
    private Long updatedByAccountId;

    @Column(nullable = true)
    private LocalDateTime updatedAt;

    @Column(nullable = true)
    private Long approvedByAccountId;

    @Column(nullable = true)
    private LocalDateTime approvedAt;

    @Column(nullable = false)
    private Integer version;
}
