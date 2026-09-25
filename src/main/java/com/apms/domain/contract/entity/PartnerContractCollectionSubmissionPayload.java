package com.apms.domain.contract.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "partner_contract_collection_submission_payloads")
public class PartnerContractCollectionSubmissionPayload {
    @Id
    private String id;
    private Long submissionId;
    private Long projectId;
    private Long taskId;
    private String targetCompanyProfileId;
    private List<String> rawDocumentIds;
    private List<String> contractDraftIds;
    private LocalDateTime createdAt;
}
