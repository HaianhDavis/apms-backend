package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfileUpdateProposal;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.apms.common.enums.SubmissionStatus;

public interface CompanyProfileUpdateProposalRepository extends MongoRepository<CompanyProfileUpdateProposal, String> {
    java.util.List<CompanyProfileUpdateProposal> findByTaskId(Long taskId);
    java.util.List<CompanyProfileUpdateProposal> findByCompanyProfileIdAndStatusIn(String companyProfileId, java.util.Collection<SubmissionStatus> statuses);
    java.util.Optional<CompanyProfileUpdateProposal> findTopByCompanyProfileIdAndOriginOrderByCreatedAtDesc(String companyProfileId, com.apms.common.enums.ProposalOrigin origin);
}
