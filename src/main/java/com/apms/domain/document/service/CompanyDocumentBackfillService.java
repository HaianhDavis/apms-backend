package com.apms.domain.document.service;

import com.apms.domain.document.dto.PublicationContext;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyDocumentBackfillService {

    private final CompanyProfileRepository companyProfileRepository;
    private final CompanyDocumentPublisher companyDocumentPublisher;

    @Transactional
    public void backfillDocuments() {
        log.info("Starting backfill for CompanyDocuments...");
        List<CompanyProfile> profiles = companyProfileRepository.findAll();
        
        int totalPublished = 0;
        
        for (CompanyProfile profile : profiles) {
            if (profile.getSourceRefs() != null && profile.getSourceRefs().getRawDocumentIds() != null) {
                for (String rawDocId : profile.getSourceRefs().getRawDocumentIds()) {
                    try {
                        companyDocumentPublisher.publishApprovedDocument(
                            profile.getCompanyId(), // using companyId (or getId() depending on standard, typically getCompanyId() or getId())
                            rawDocId,
                            null, // System backfill
                            LocalDateTime.now(),
                            PublicationContext.builder()
                                .description("System backfill")
                                .build()
                        );
                        totalPublished++;
                    } catch (Exception e) {
                        log.error("Failed to backfill document {} for profile {}", rawDocId, profile.getCompanyId(), e);
                    }
                }
            }
        }
        
        log.info("Finished backfill for CompanyDocuments. Published {} documents.", totalPublished);
    }
}
