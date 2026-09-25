package com.apms.domain.document.service;

import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.PublicationContext;
import com.apms.domain.document.entity.CompanyDocument;
import com.apms.domain.document.repository.mongo.CompanyDocumentRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.common.exception.BusinessValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyDocumentPublisher {
    private static final String DOCUMENT_TYPE_PARTNER_CONTRACT = "PARTNER_CONTRACT";

    private final CompanyDocumentRepository companyDocumentRepository;
    private final RawDocumentRepository rawDocumentRepository;

    @Transactional
    public void publishApprovedDocument(String companyProfileId, String sourceDocumentId, Long approvedBy, LocalDateTime approvedAt, PublicationContext ctx) {
        if (companyProfileId == null || sourceDocumentId == null) {
            log.warn("Cannot publish document with null companyProfileId or sourceDocumentId");
            return;
        }
        if (ctx == null || !DOCUMENT_TYPE_PARTNER_CONTRACT.equals(ctx.getDocumentType())) {
            throw new BusinessValidationException("Company Profile documents can only be published from approved partner contracts");
        }

        RawDocument rawDocument = rawDocumentRepository.findById(sourceDocumentId)
                .orElseThrow(() -> new BusinessValidationException("RawDocument not found: " + sourceDocumentId));
        if (rawDocument.getSource() == null || !DOCUMENT_TYPE_PARTNER_CONTRACT.equalsIgnoreCase(rawDocument.getSource().getType())) {
            throw new BusinessValidationException("Only PARTNER_CONTRACT raw documents can be published to Company Profile documents");
        }

        CompanyDocument document = companyDocumentRepository.findByCompanyProfileIdAndSourceDocumentId(companyProfileId, sourceDocumentId)
                .orElseGet(() -> {
                    CompanyDocument newDoc = new CompanyDocument();
                    newDoc.setCompanyProfileId(companyProfileId);
                    newDoc.setSourceDocumentId(sourceDocumentId);
                    newDoc.setCreatedAt(LocalDateTime.now());
                    
                    // Fetch RawDocument to get initial metadata if needed
                    if (rawDocument.getMetadata() != null) {
                        newDoc.setUploadedBy(rawDocument.getMetadata().getUploadedBy());
                        newDoc.setUploadedAt(rawDocument.getMetadata().getUploadedAt());
                    }
                    if (rawDocument.getSource() != null && newDoc.getDisplayName() == null) {
                        newDoc.setDisplayName(rawDocument.getSource().getFileName());
                    }
                    
                    return newDoc;
                });

        document.setApprovedBy(approvedBy);
        document.setApprovedAt(approvedAt);
        document.setPublishedBy(approvedBy); // Usually same as approvedBy in this context
        document.setPublishedAt(LocalDateTime.now());
        document.setStatus("PUBLISHED");
        document.setUpdatedAt(LocalDateTime.now());

        if (ctx != null) {
            if (ctx.getSourceProjectId() != null) document.setSourceProjectId(ctx.getSourceProjectId());
            if (ctx.getSourceTaskId() != null) document.setSourceTaskId(ctx.getSourceTaskId());
            if (ctx.getSourceSubmissionId() != null) document.setSourceSubmissionId(ctx.getSourceSubmissionId());
            document.setSourceCandidateId(ctx.getSourceCandidateId());
            if (ctx.getDocumentType() != null) document.setDocumentType(ctx.getDocumentType());
            if (ctx.getDescription() != null) document.setDescription(ctx.getDescription());
            if (ctx.getDisplayName() != null) document.setDisplayName(ctx.getDisplayName());
        }

        companyDocumentRepository.save(document);
        log.info("Published CompanyDocument for profile {} and source {}", companyProfileId, sourceDocumentId);
    }
}
