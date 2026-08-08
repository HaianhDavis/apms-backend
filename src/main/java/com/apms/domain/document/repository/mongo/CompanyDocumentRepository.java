package com.apms.domain.document.repository.mongo;

import com.apms.domain.document.entity.CompanyDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyDocumentRepository extends MongoRepository<CompanyDocument, String> {
    Optional<CompanyDocument> findByCompanyProfileIdAndSourceDocumentId(String companyProfileId, String sourceDocumentId);
    Page<CompanyDocument> findByCompanyProfileIdAndStatusAndDeletedAtIsNull(String companyProfileId, String status, Pageable pageable);
}
