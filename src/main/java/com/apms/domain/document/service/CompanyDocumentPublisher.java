package com.apms.domain.document.service;

import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.PublicationContext;
import com.apms.domain.document.entity.CompanyDocument;
import com.apms.domain.document.repository.mongo.CompanyDocumentRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyDocumentPublisher {

    private final CompanyDocumentRepository companyDocumentRepository;
    private final RawDocumentRepository rawDocumentRepository;

    @Transactional
    public void publishApprovedDocument(String companyProfileId, String sourceDocumentId, Long approvedBy, LocalDateTime approvedAt, PublicationContext ctx) {
        if (companyProfileId == null || sourceDocumentId == null) {
            log.warn("Cannot publish document with null companyProfileId or sourceDocumentId");
            return;
        }

        CompanyDocument document = companyDocumentRepository.findByCompanyProfileIdAndSourceDocumentId(companyProfileId, sourceDocumentId)
                .orElseGet(() -> {
                    CompanyDocument newDoc = new CompanyDocument();
                    newDoc.setCompanyProfileId(companyProfileId);
                    newDoc.setSourceDocumentId(sourceDocumentId);
                    newDoc.setCreatedAt(LocalDateTime.now());
                    
                    // Fetch RawDocument to get initial metadata if needed
                    rawDocumentRepository.findById(sourceDocumentId).ifPresent(rawDoc -> {
                        if (rawDoc.getMetadata() != null) {
                            newDoc.setUploadedBy(rawDoc.getMetadata().getUploadedBy());
                            newDoc.setUploadedAt(rawDoc.getMetadata().getUploadedAt());
                        }
                        if (rawDoc.getSource() != null && newDoc.getDisplayName() == null) {
                            newDoc.setDisplayName(rawDoc.getSource().getFileName());
                        }
                    });
                    
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
            if (ctx.getSourceCandidateId() != null) document.setSourceCandidateId(ctx.getSourceCandidateId());
            if (ctx.getDocumentType() != null) document.setDocumentType(ctx.getDocumentType());
            if (ctx.getDescription() != null) document.setDescription(ctx.getDescription());
            if (ctx.getDisplayName() != null) document.setDisplayName(ctx.getDisplayName());
        }

        companyDocumentRepository.save(document);
        log.info("Published CompanyDocument for profile {} and source {}", companyProfileId, sourceDocumentId);
    }
}
