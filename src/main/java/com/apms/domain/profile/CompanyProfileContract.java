package com.apms.domain.profile;

import com.apms.domain.contract.enums.ContractStatus;
import com.apms.domain.contract.enums.ContractType;
import com.apms.domain.contract.model.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Document(collection = "company_profile_contracts")
@CompoundIndexes({
    @CompoundIndex(name = "profile_source_contract_idx", def = "{'companyProfileId': 1, 'sourceResearchId': 1, 'sourceContractEntryId': 1}"),
    @CompoundIndex(name = "profile_contracts_idx", def = "{'companyProfileId': 1, 'createdAt': -1}")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfileContract {

    @Id
    private String id;

    @Indexed
    private String companyProfileId;

    private String title;
    private ContractType contractType;
    private ContractStatus derivedContractStatus;
    private LocalDate documentDate;

    // Core contract data with preserved source evidence
    private CommonContractData commonData;

    // Subtype payloads
    private CooperationAgreementData cooperationAgreementData;
    private PartnershipAgreementData partnershipAgreementData;
    private JointVentureAgreementData jointVentureAgreementData;
    private BusinessCooperationContractData businessCooperationContractData;

    /**
     * PROMOTED (from approved ContractResearch) or MANUAL (future).
     */
    @Builder.Default
    private String sourceType = "PROMOTED";

    // Provenance references
    private String sourceResearchId;
    private String sourceContractEntryId;
    private String sourceDocumentId;
    private String sourceDocumentName;
    private Long projectId;
    private Long taskId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long lastModifiedBy;
}
