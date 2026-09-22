package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfileContract;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompanyProfileContractRepository extends MongoRepository<CompanyProfileContract, String> {

    List<CompanyProfileContract> findByCompanyProfileIdOrderByCreatedAtDesc(String companyProfileId);

    List<CompanyProfileContract> findByCompanyProfileIdInOrderByCreatedAtDesc(java.util.Collection<String> companyProfileIds);

    List<CompanyProfileContract> findByCompanyProfileId(String companyProfileId);

    Optional<CompanyProfileContract> findByCompanyProfileIdAndId(String companyProfileId, String id);

    Optional<CompanyProfileContract> findByCompanyProfileIdInAndId(java.util.Collection<String> companyProfileIds, String id);

    boolean existsByCompanyProfileIdAndSourceResearchIdAndSourceContractEntryId(
            String companyProfileId,
            String sourceResearchId,
            String sourceContractEntryId
    );

    boolean existsBySourceResearchIdAndSourceContractEntryId(
            String sourceResearchId,
            String sourceContractEntryId
    );

    Optional<CompanyProfileContract> findByCompanyProfileIdAndSourceResearchIdAndSourceContractEntryId(
            String companyProfileId,
            String sourceResearchId,
            String sourceContractEntryId
    );

    Optional<CompanyProfileContract> findBySourceResearchIdAndSourceContractEntryId(
            String sourceResearchId,
            String sourceContractEntryId
    );
}
