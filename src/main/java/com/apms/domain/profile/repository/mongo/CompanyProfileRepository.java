package com.apms.domain.profile.repository.mongo;

import com.apms.domain.profile.CompanyProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

public interface CompanyProfileRepository extends MongoRepository<CompanyProfile, String> {

    Optional<CompanyProfile> findByCompanyId(String companyId);

    @Query("{ 'identity.name': { $regex: ?0, $options: 'i' } }")
    Page<CompanyProfile> searchByName(String name, Pageable pageable);

    @Query("{ 'sourceRefs.candidateIds': ?0 }")
    Optional<CompanyProfile> findByCandidateId(String candidateId);

    @Query("{ 'sourceRefs.projectIds': ?0 }")
    java.util.List<CompanyProfile> findByProjectId(String projectId);
}
