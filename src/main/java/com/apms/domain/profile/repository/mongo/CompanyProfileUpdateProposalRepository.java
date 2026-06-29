package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfileUpdateProposal;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CompanyProfileUpdateProposalRepository extends MongoRepository<CompanyProfileUpdateProposal, String> {
}
