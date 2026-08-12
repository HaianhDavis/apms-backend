package com.apms.domain.document.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "company_documents")
@CompoundIndex(name = "uk_company_document_profile_source", def = "{'companyProfileId': 1, 'sourceDocumentId': 1}", unique = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyDocument {

    @Id
    private String id;

    private String companyProfileId;
    private String sourceDocumentId;
    private String sourceProjectId;
    private String sourceTaskId;
    private String sourceSubmissionId;
    private String sourceCandidateId;
    private String displayName;
    private String documentType;
    private String description;
    
    @Builder.Default
    private String status = "PUBLISHED";
    
    private String uploadedBy;
    private LocalDateTime uploadedAt;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private Long publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
