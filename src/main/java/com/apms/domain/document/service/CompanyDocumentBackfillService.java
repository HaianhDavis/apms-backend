package com.apms.domain.document.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class CompanyDocumentBackfillService {

    @Transactional
    public void backfillDocuments() {
        log.info("CompanyDocumentBackfillService is disabled: Company Profile documents are published only from approved PARTNER_CONTRACT_COLLECTION packages.");
    }
}
