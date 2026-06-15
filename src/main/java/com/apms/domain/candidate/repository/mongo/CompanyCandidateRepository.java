package com.apms.domain.candidate.repository.mongo;

import com.apms.common.enums.CandidateStatus;
import com.apms.domain.candidate.CompanyCandidate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CompanyCandidateRepository extends MongoRepository<CompanyCandidate, String> {
    Page<CompanyCandidate> findByProjectId(String projectId, Pageable pageable);
    Page<CompanyCandidate> findByStatus(CandidateStatus status, Pageable pageable);
    Page<CompanyCandidate> findByProjectIdAndStatus(String projectId, CandidateStatus status, Pageable pageable);
    
    long countByStatus(CandidateStatus status);
}
