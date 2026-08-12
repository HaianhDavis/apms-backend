package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

public interface CompanyProfileRepository extends MongoRepository<CompanyProfile, String> {

    Optional<CompanyProfile> findByCompanyId(String companyId);
    
    java.util.List<CompanyProfile> findByCompanyIdIn(java.util.Collection<String> companyIds);

    @Query("{ 'identity.name': { $regex: ?0, $options: 'i' } }")
    Page<CompanyProfile> searchByName(String name, Pageable pageable);

    @Query("{ 'sourceRefs.candidateIds': ?0 }")
    Optional<CompanyProfile> findByCandidateId(String candidateId);

    @Query("{ 'sourceRefs.projectIds': ?0 }")
    java.util.List<CompanyProfile> findByProjectId(String projectId);

    @Query("{ 'sourceRefs.projectIds': { $exists: true, $ne: [] } }")
    java.util.List<CompanyProfile> findCompaniesInAnyProject();

    @Query("{ $and: [ { 'identity.stockTicker': { $exists: true, $ne: '' } }, "
            + "{ 'identity.stockExchange': { $exists: true, $nin: [null, 'NONE'] } } ] }")
    java.util.List<CompanyProfile> findListedCompanies();

    @Query(value = "{ 'isDeleted': { $ne: true }, '_id': { $ne: ?0 }, "
            + "'sourceRefs.projectIds.0': { $exists: true } }", count = true)
    long countProjectScopedProfilesExcludingCompany(String companyProfileId);
}
